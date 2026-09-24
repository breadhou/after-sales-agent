package com.mall.agent.config;

import com.mall.agent.agent.DecisionAgent;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.RefundRequestTools;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    private final RefundReviewContext context = new RefundReviewContext(
            "用户原话：请退货", "{\"id\":9001,\"status\":\"RECEIVED\"}",
            "{\"orderId\":9001,\"eligible\":true}",
            new CandidateRefundAction(9001L, "不想要了"));

    @Test
    void reviewExceptionMustReject() {
        ReviewVerdict verdict = AgentConfig.reviewSafely(message -> {
            throw new IllegalStateException("模型服务异常");
        }, context);

        assertFalse(verdict.approved());
    }

    @Test
    void absentVerdictMustReject() {
        ReviewVerdict verdict = AgentConfig.reviewSafely(message -> null, context);

        assertFalse(verdict.approved());
    }

    @Test
    void mustStopWhenMcpDoesNotAdvertiseExactlyTheReadOnlyToolSet() {
        McpClient client = mcpClient(List.of(tool("get_order"), tool("submit_refund")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentConfig.requireReadOnlyTools(client));

        assertTrue(e.getMessage().contains("MCP"), e.getMessage());
    }

    @Test
    void mustStopWhenMcpToolListingFails() {
        McpClient client = (McpClient) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("listTools")) {
                        throw new IllegalStateException("server unavailable");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentConfig.requireReadOnlyTools(client));

        assertTrue(e.getMessage().contains("MCP"), e.getMessage());
    }

    @Test
    void localToolNamesMustMatchDecisionPrompt() throws NoSuchMethodException {
        Tool requestRefund = RefundRequestTools.class
                .getMethod("requestRefund", Long.class, String.class).getAnnotation(Tool.class);
        Tool escalate = EscalationTools.class
                .getMethod("escalateToHuman", Long.class, String.class).getAnnotation(Tool.class);

        assertEquals("request_refund", requestRefund.name());
        assertEquals("escalate_to_human", escalate.name());
    }

    @Test
    void localToolParameterNamesMustMatchTheirContracts() throws NoSuchMethodException {
        ToolSpecification refund = ToolSpecifications.toolSpecificationFrom(RefundRequestTools.class
                .getMethod("requestRefund", Long.class, String.class));
        ToolSpecification escalation = ToolSpecifications.toolSpecificationFrom(EscalationTools.class
                .getMethod("escalateToHuman", Long.class, String.class));

        assertEquals(List.of("orderId", "reason"),
                refund.parameters().properties().keySet().stream().sorted().toList());
        assertEquals(List.of("orderId", "summary"),
                escalation.parameters().properties().keySet().stream().sorted().toList());
    }

    @Test
    void decisionModelReceivesOnlyFiveReadOnlyMcpToolsAndTwoLocalTools() {
        CapturingChatModel model = new CapturingChatModel("已处理");
        List<ToolSpecification> advertised = List.of(
                tool("get_order"), tool("list_user_orders"), tool("get_logistics"),
                tool("get_refund_eligibility"), tool("list_policy_clauses"), tool("submit_refund"));
        DecisionAgent decision = AgentConfig.decisionAgent(model, mcpClient(advertised),
                refundTools(), new EscalationTools("session-1", ignored -> { }));

        decision.handle("session-1", "请帮我退款");

        List<ToolSpecification> tools = model.request().toolSpecifications();
        assertEquals(7, tools.size());
        assertEquals(Set.of("get_order", "list_user_orders", "get_logistics",
                        "get_refund_eligibility", "list_policy_clauses",
                        "request_refund", "escalate_to_human"),
                tools.stream().map(ToolSpecification::name).collect(java.util.stream.Collectors.toSet()));
        assertFalse(tools.stream().map(ToolSpecification::name).anyMatch("submit_refund"::equals));
    }

    @Test
    void reviewModelReceivesNoTools() {
        CapturingChatModel model = new CapturingChatModel("{\"approved\":false,\"faults\":[]}");
        ReviewVerdict verdict = AgentConfig.reviewAgent(model).review("请复核");

        assertFalse(verdict.approved());
        assertTrue(model.request().toolSpecifications() == null
                || model.request().toolSpecifications().isEmpty());
    }

    @Test
    void mcpChildReceivesUserCredentialsButNoInheritedSecrets() {
        var environment = AgentConfig.mcpChildEnvironment("http://localhost:8081", "synthetic-user-token");

        assertEquals("http://localhost:8081", environment.get("SUPERMALL_BASE_URL"));
        assertEquals("synthetic-user-token", environment.get("SUPERMALL_TOKEN"));
        for (String key : List.of("MODEL_API_KEY", "MERCHANT_JWT_SECRET",
                "SPRING_DATASOURCE_PASSWORD", "SPRING_RABBITMQ_PASSWORD")) {
            assertEquals("", environment.get(key), key + " must be cleared in the MCP child");
        }
    }

    private static RefundRequestTools refundTools() {
        return new RefundRequestTools(
                (orderId, reason) -> null,
                review -> ReviewVerdict.rejected("不应调用"),
                (orderId, reason) -> "不应调用",
                (orderId, summary) -> "不应调用",
                "session-1");
    }

    private static McpClient mcpClient(List<ToolSpecification> tools) {
        return (McpClient) Proxy.newProxyInstance(
                AgentConfigTest.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("listTools")) {
                        return tools;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ToolSpecification tool(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private static final class CapturingChatModel implements ChatModel {
        private final String response;
        private final AtomicReference<ChatRequest> request = new AtomicReference<>();

        private CapturingChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            this.request.set(request);
            return ChatResponse.builder().aiMessage(new AiMessage(response)).build();
        }

        private ChatRequest request() {
            return request.get();
        }
    }
}
