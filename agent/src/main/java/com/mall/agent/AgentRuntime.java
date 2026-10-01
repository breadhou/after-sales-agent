package com.mall.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.agent.DecisionAgent;
import com.mall.agent.config.AgentConfig;
import com.mall.agent.flow.ConversationCoordinator;
import com.mall.agent.flow.FlowObserver;
import com.mall.agent.flow.RefundWorkflow;
import com.mall.agent.knowledge.ExplanationService;
import com.mall.agent.model.EscalationRecord;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.ExplanationRequestTools;
import com.mall.agent.tools.RefundExecutor;
import com.mall.agent.tools.RefundHandoffTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared production assembly. Each runtime owns fresh session state; its caller owns the MCP client. */
public final class AgentRuntime {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConversationCoordinator coordinator;
    private final EscalationTools escalations;

    private AgentRuntime(ConversationCoordinator coordinator, EscalationTools escalations) {
        this.coordinator = coordinator;
        this.escalations = escalations;
    }

    /** Does not close MCP, including when construction fails. Model configuration belongs to the caller. */
    public static AgentRuntime create(ChatModel dialogue, ChatModel review, ChatModel explanation,
                                      McpClient mcp, Set<Long> allowedProducts, String sessionId,
                                      FlowObserver observer, Consumer<EscalationRecord> escalationSink) {
        FlowObserver sharedObserver = Objects.requireNonNullElse(observer, FlowObserver.NOOP);
        EscalationTools escalation = new EscalationTools(sessionId, escalationSink);
        RefundHandoffTools handoff = new RefundHandoffTools();
        ExplanationRequestTools explanationTools = new ExplanationRequestTools();
        DecisionAgent agent = AgentConfig.decisionAgent(dialogue, mcp, handoff, escalation, explanationTools);
        ExplanationService explanationService = explanationService(mcp, explanation, allowedProducts, sharedObserver);
        RefundExecutor executor = new RefundExecutor(mcp);
        RefundWorkflow workflow = new RefundWorkflow(mcp,
                context -> AgentConfig.reviewSafely(AgentConfig.reviewAgent(review), context, sharedObserver),
                executor::apply, escalation, sharedObserver);
        ConversationCoordinator coordinator = new ConversationCoordinator(agent, handoff, escalation,
                () -> listedOrderIds(mcp::executeTool),
                orderId -> eligibilityReply(orderId, mcp::executeTool),
                orderId -> isOrderRefunded(orderId, mcp::executeTool),
                workflow::apply, explanationTools, explanationService::answer, sharedObserver);
        return new AgentRuntime(coordinator, escalation);
    }

    public ConversationCoordinator coordinator() {
        return coordinator;
    }

    public EscalationTools escalations() {
        return escalations;
    }

    static ExplanationService explanationService(McpClient mcp, ChatModel model,
                                                 Set<Long> allowedProducts, FlowObserver observer) {
        return new ExplanationService(mcp, allowedProducts, AgentConfig.explanationGenerator(model), observer);
    }

    /** 用用户 JWT 的 MCP 客户端读清单；只有实际返回的正整数 ID 可被选择。 */
    static List<Long> listedOrderIds(Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        try {
            ToolExecutionResult result = toolCaller.apply(ToolExecutionRequest.builder()
                    .name("list_user_orders").arguments("{}").build());
            JsonNode root = trustedObject(result);
            if (root == null || !root.path("records").isArray()) {
                return List.of();
            }
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            for (JsonNode record : root.path("records")) {
                JsonNode id = record.path("id");
                if (id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0) {
                    ids.add(id.longValue());
                }
            }
            return List.copyOf(ids);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 资格询问由可信 MCP 事实形成固定回复，不交给决策模型解释。 */
    static String eligibilityReply(Long orderId,
                                   Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        String unavailable = "订单 " + orderId + " 的退款资格无法确认；本次未提交退款。";
        if (orderId == null || orderId <= 0) {
            return unavailable;
        }
        try {
            ToolExecutionResult result = toolCaller.apply(ToolExecutionRequest.builder()
                    .name("get_refund_eligibility")
                    .arguments("{\"orderId\":" + orderId + "}").build());
            JsonNode fact = trustedObject(result);
            if (fact == null || !fact.path("orderId").isIntegralNumber()
                    || !fact.path("orderId").canConvertToLong()
                    || fact.path("orderId").longValue() != orderId
                    || !fact.path("eligible").isBoolean()
                    || !fact.path("refundExists").isBoolean()) {
                return unavailable;
            }
            if (fact.path("eligible").booleanValue() && !fact.path("refundExists").booleanValue()) {
                return "订单 " + orderId + " 当前查询显示可申请退款；本次未提交退款。";
            }
            JsonNode reason = fact.path("reason");
            if (!fact.path("eligible").booleanValue()
                    && reason.isTextual() && !reason.textValue().isBlank()) {
                return "订单 " + orderId + " 当前不可退：" + reason.textValue() + "。本次未提交退款。";
            }
            return unavailable;
        } catch (RuntimeException e) {
            return unavailable;
        }
    }

    /** 仅当前用户 JWT 的新鲜 get_order 状态可证明历史上已退款。 */
    static boolean isOrderRefunded(Long orderId,
                                   Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        try {
            ToolExecutionResult result = toolCaller.apply(ToolExecutionRequest.builder()
                    .name("get_order").arguments("{\"orderId\":" + orderId + "}").build());
            JsonNode fact = trustedObject(result);
            JsonNode id = fact == null ? null : fact.path("id");
            return id != null && id.isIntegralNumber() && id.canConvertToLong()
                    && id.longValue() == orderId
                    && fact.path("status").isTextual()
                    && "REFUNDED".equals(fact.path("status").textValue());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static JsonNode trustedObject(ToolExecutionResult result) {
        if (result == null || result.isError() || result.resultText() == null
                || result.resultText().isBlank()) {
            return null;
        }
        try {
            JsonNode fact = MAPPER.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(result.resultText());
            return fact != null && fact.isObject() ? fact : null;
        } catch (IOException e) {
            return null;
        }
    }
}
