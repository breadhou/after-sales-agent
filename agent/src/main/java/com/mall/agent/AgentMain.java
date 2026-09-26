package com.mall.agent;

import com.mall.agent.agent.DecisionAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.config.AgentConfig;
import com.mall.agent.config.ModelProperties;
import com.mall.agent.flow.ConversationCoordinator;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.RefundHandoffTools;
import com.mall.agent.tools.RefundRequestTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

/** 命令行入口。一个进程对应一个用户会话，令牌只从环境变量读取。 */
public final class AgentMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentMain() {
    }

    public static void main(String[] args) throws Exception {
        rejectBackendCredentials(System.getenv());
        String token = requiredEnvironment("SUPERMALL_TOKEN", System.getenv());
        ModelProperties modelProperties = modelProperties(loadProperties(), System.getenv());
        String supermallBaseUrl = optionalEnvironment(
                "SUPERMALL_BASE_URL", "http://localhost:8081", System.getenv());
        Path mcpJar = mcpServerJar(Path.of("").toAbsolutePath());

        ChatModel model = AgentConfig.chatModel(modelProperties);
        McpClient mcp = AgentConfig.mcpClient(mcpJar.toString(), supermallBaseUrl, token);
        try {
            String sessionId = UUID.randomUUID().toString();
            EscalationTools escalation = new EscalationTools(sessionId, record ->
                    System.err.println("[升级人工] 请求已记录"));
            RefundHandoffTools handoff = new RefundHandoffTools();
            DecisionAgent agent = AgentConfig.decisionAgent(model, mcp, handoff, escalation);
            ConversationCoordinator coordinator = new ConversationCoordinator(agent, handoff, escalation,
                    () -> listedOrderIds(mcp::executeTool),
                    orderId -> eligibilityReply(orderId, mcp::executeTool),
                    (confirmedSession, request) -> "订单 " + request.orderId()
                            + " 的退款申请已确认；退款流程尚未接入，本次未执行退款。");

            System.out.println("售后客服已就绪（会话 " + sessionId + "）。输入 exit 退出。");
            runSession(coordinator, sessionId,
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        } finally {
            mcp.close();
        }
    }

    static ModelProperties modelProperties(Properties properties, Map<String, String> environment) {
        Properties resolved = new Properties();
        resolved.putAll(properties);
        copyRequiredModelEnvironment(resolved, environment, "MODEL_BASE_URL", "model.baseUrl");
        copyRequiredModelEnvironment(resolved, environment, "MODEL_API_KEY", "model.apiKey");
        copyRequiredModelEnvironment(resolved, environment, "MODEL_NAME", "model.name");
        return ModelProperties.from(resolved);
    }

    static void rejectBackendCredentials(Map<String, String> environment) {
        for (String key : new String[]{"MERCHANT_JWT_SECRET",
                "SPRING_DATASOURCE_PASSWORD", "SPRING_RABBITMQ_PASSWORD"}) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) {
                throw new IllegalStateException("Agent 不应持有后端凭据：" + key
                        + "。请使用仅传入模型配置与用户令牌的启动器。");
            }
        }
    }

    static Path mcpServerJar(Path repositoryRoot) {
        Path jar = repositoryRoot.resolve("mcp-server").resolve("target")
                .resolve("mcp-server.jar").toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("找不到 MCP server 包：" + jar
                    + "。请先在仓库根目录运行 Maven package。");
        }
        return jar;
    }

    private static Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        try (var input = AgentMain.class.getClassLoader().getResourceAsStream("agent.properties")) {
            if (input == null) {
                throw new IllegalStateException("找不到 agent.properties");
            }
            properties.load(input);
        }
        return properties;
    }

    static void runSession(ConversationCoordinator coordinator, String sessionId,
                           BufferedReader reader, PrintStream output) throws IOException {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
                output.println("\n客服：" + coordinator.handleTurn(sessionId, line) + "\n");
            }
        }
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

    private static JsonNode trustedObject(ToolExecutionResult result) {
        if (result == null || result.isError() || result.resultText() == null
                || result.resultText().isBlank()) {
            return null;
        }
        try {
            JsonNode fact = MAPPER.readTree(result.resultText());
            return fact != null && fact.isObject() ? fact : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 仅为 Task 7 迁移前的旧单元测试保留，CLI 已不再使用旧退款工具。 */
    static String finalResponse(String modelResponse, RefundRequestTools refundTools) {
        String trustedReply = refundTools.takeAuthoritativeReply();
        return trustedReply != null ? trustedReply : modelResponse;
    }

    private static void copyRequiredModelEnvironment(Properties properties,
                                                     Map<String, String> environment,
                                                     String environmentKey, String propertyKey) {
        String value = environment.get(environmentKey);
        if (value != null && !value.isBlank()) {
            properties.setProperty(propertyKey, value);
        }
    }

    private static String requiredEnvironment(String key, Map<String, String> environment) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 未设置，无法启动");
        }
        return value;
    }

    private static String optionalEnvironment(String key, String fallback,
                                              Map<String, String> environment) {
        String value = environment.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
