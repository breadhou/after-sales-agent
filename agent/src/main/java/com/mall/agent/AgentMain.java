package com.mall.agent;

import com.mall.agent.config.AgentConfig;
import com.mall.agent.config.ModelProperties;
import com.mall.agent.flow.ConversationCoordinator;
import com.mall.agent.flow.FlowObserver;
import com.mall.agent.knowledge.DemoProductManifest;
import com.mall.agent.knowledge.ExplanationService;
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
import java.nio.file.InvalidPathException;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 命令行入口。一个进程对应一个用户会话，令牌只从环境变量读取。 */
public final class AgentMain {

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
            AgentRuntime runtime = createRuntime(mcp, model, System.getenv(), sessionId);

            System.out.println("售后客服已就绪（会话 " + sessionId + "）。输入 exit 退出。");
            runSession(runtime.coordinator(), sessionId,
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        } finally {
            mcp.close();
        }
    }

    static AgentRuntime createRuntime(McpClient mcp, ChatModel model,
                                      Map<String, String> environment, String sessionId) {
        return AgentRuntime.create(model, model, model, mcp, allowedProducts(environment), sessionId,
                FlowObserver.NOOP, record -> System.err.println("[升级人工] 请求已记录"));
    }

    /** Optional local manifest only supplies allowlisted IDs; all product facts are freshly read via MCP. */
    static ExplanationService explanationService(McpClient mcp, ChatModel model,
                                                 Map<String, String> environment) {
        return AgentRuntime.explanationService(mcp, model, allowedProducts(environment), FlowObserver.NOOP);
    }

    private static Set<Long> allowedProducts(Map<String, String> environment) {
        String manifest = environment.get("DEMO_PRODUCT_MANIFEST");
        Set<Long> allowed;
        try {
            allowed = manifest == null || manifest.isBlank()
                    ? Set.of() : DemoProductManifest.load(Path.of(manifest));
        } catch (InvalidPathException e) {
            allowed = Set.of();
        }
        return allowed;
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
        return AgentRuntime.listedOrderIds(toolCaller);
    }

    /** 资格询问由可信 MCP 事实形成固定回复，不交给决策模型解释。 */
    static String eligibilityReply(Long orderId,
                                   Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        return AgentRuntime.eligibilityReply(orderId, toolCaller);
    }

    /** 仅当前用户 JWT 的新鲜 get_order 状态可证明历史上已退款。 */
    static boolean isOrderRefunded(Long orderId,
                                   Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        return AgentRuntime.isOrderRefunded(orderId, toolCaller);
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
