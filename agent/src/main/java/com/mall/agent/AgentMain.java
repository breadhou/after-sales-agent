package com.mall.agent;

import com.mall.agent.agent.DecisionAgent;
import com.mall.agent.agent.ReviewAgent;
import com.mall.agent.config.AgentConfig;
import com.mall.agent.config.ModelProperties;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.RefundExecutor;
import com.mall.agent.tools.RefundRequestTools;
import com.mall.agent.tools.RefundReviewContextFactory;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
            ReviewAgent reviewer = AgentConfig.reviewAgent(model);
            String sessionId = UUID.randomUUID().toString();
            AtomicReference<String> originalUserRequest = new AtomicReference<>();
            EscalationTools escalation = new EscalationTools(sessionId, record ->
                    System.err.println("[升级人工] 请求已记录"));
            RefundRequestTools refundTools = new RefundRequestTools(
                    new RefundReviewContextFactory(mcp, originalUserRequest::get),
                    context -> AgentConfig.reviewSafely(reviewer, context),
                    new RefundExecutor(mcp),
                    escalation::escalateToHuman,
                    sessionId);
            DecisionAgent agent = AgentConfig.decisionAgent(model, mcp, refundTools, escalation);

            System.out.println("售后客服已就绪（会话 " + sessionId + "）。输入 exit 退出。");
            runSession(agent, sessionId, originalUserRequest);
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

    private static void runSession(DecisionAgent agent, String sessionId,
                                   AtomicReference<String> originalUserRequest) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
                originalUserRequest.set(line);
                System.out.println("\n客服：" + agent.handle(sessionId, line) + "\n");
            }
        }
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
