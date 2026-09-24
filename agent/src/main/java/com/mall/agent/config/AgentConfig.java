package com.mall.agent.config;

import com.mall.agent.agent.DecisionAgent;
import com.mall.agent.agent.ReviewAgent;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.RefundRequestTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 装配模型、MCP 客户端及具有隔离工具面的两个 Agent。 */
public final class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /** 决策 Agent 唯一可见的 MCP 工具，全部只读。 */
    static final List<String> READ_ONLY_TOOLS = List.of(
            "get_order", "list_user_orders", "get_logistics",
            "get_refund_eligibility", "list_policy_clauses");

    private AgentConfig() {
    }

    public static ChatModel chatModel(ModelProperties props) {
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.name())
                .temperature(props.temperature())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /** 拉起 MCP server 子进程；用户令牌只经环境变量传给子进程。 */
    public static McpClient mcpClient(String jarPath, String supermallBaseUrl, String userToken) {
        var transport = new StdioMcpTransport.Builder()
                .command(List.of("java", "-jar", jarPath))
                .environment(mcpChildEnvironment(supermallBaseUrl, userToken))
                .logEvents(false)
                .build();

        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("after-sales-agent")
                .clientVersion("1.0.0")
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** SDK 在父环境上覆盖这些键；空值阻止子进程继承模型和后端密钥。 */
    static Map<String, String> mcpChildEnvironment(String supermallBaseUrl, String userToken) {
        return Map.of(
                "SUPERMALL_BASE_URL", supermallBaseUrl,
                "SUPERMALL_TOKEN", userToken,
                "MODEL_API_KEY", "",
                "MERCHANT_JWT_SECRET", "",
                "SPRING_DATASOURCE_PASSWORD", "",
                "SPRING_RABBITMQ_PASSWORD", "");
    }

    public static DecisionAgent decisionAgent(ChatModel model, McpClient mcp,
                                              RefundRequestTools refundTools,
                                              EscalationTools escalationTools) {
        requireReadOnlyTools(mcp);
        McpToolProvider readOnlyTools = McpToolProvider.builder()
                .mcpClients(mcp)
                .filterToolNames(READ_ONLY_TOOLS.toArray(String[]::new))
                .failIfOneServerFails(true)
                .build();

        return AiServices.builder(DecisionAgent.class)
                .chatModel(model)
                .toolProvider(readOnlyTools)
                .tools(refundTools, escalationTools)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .maxToolCallingRoundTrips(10)
                .build();
    }

    public static ReviewAgent reviewAgent(ChatModel model) {
        return AiServices.builder(ReviewAgent.class)
                .chatModel(model)
                .build();
    }

    /** 工具列举失败或只读工具不完整时，拒绝启动以免模型在错误工具面上运行。 */
    static void requireReadOnlyTools(McpClient mcp) {
        final List<ToolSpecification> tools;
        try {
            tools = mcp.listTools();
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法列出 MCP 工具，Agent 未启动", e);
        }
        if (tools == null) {
            throw new IllegalStateException("无法列出 MCP 工具，Agent 未启动");
        }
        Set<String> names = new HashSet<>();
        for (ToolSpecification tool : tools) {
            if (tool != null && tool.name() != null) {
                names.add(tool.name());
            }
        }
        if (!names.containsAll(READ_ONLY_TOOLS)) {
            throw new IllegalStateException("MCP 工具集缺少决策所需的只读工具，Agent 未启动");
        }
    }

    /** 复核失败时保守驳回，避免任何异常成为绕过复核的通路。 */
    public static ReviewVerdict reviewSafely(ReviewAgent agent, RefundReviewContext context) {
        try {
            ReviewVerdict verdict = agent.review("""
                    原始用户诉求：%s
                    可信订单事实：%s
                    可信资格事实：%s
                    候选退款动作：订单 %s，原因：%s
                    """.formatted(
                    context.originalUserRequest(),
                    context.trustedOrder(),
                    context.trustedEligibility(),
                    context.candidateAction().orderId(),
                    context.candidateAction().reason()));
            return verdict == null ? ReviewVerdict.rejected("复核未返回结论") : verdict;
        } catch (Exception e) {
            log.error("复核调用失败，按驳回处理", e);
            return ReviewVerdict.rejected("复核系统异常");
        }
    }
}
