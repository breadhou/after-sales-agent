package com.mall.agent;

import com.mall.agent.config.ModelProperties;
import com.mall.agent.flow.ConversationCoordinator;
import com.mall.agent.tools.RefundHandoffTools;
import com.mall.agent.tools.EscalationTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMainTest {

    @Test
    void trustedOrderAndEligibilityQueriesUseMcpFacts() {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        List<Long> listed = AgentMain.listedOrderIds(request -> {
            calls.add(request);
            return ToolExecutionResult.builder().isError(false)
                    .resultText("{\"records\":[{\"id\":9001},{\"id\":9002},{\"id\":9001}]}")
                    .build();
        });
        assertEquals(List.of(9001L, 9002L), listed);
        assertEquals("list_user_orders", calls.get(0).name());

        String reply = AgentMain.eligibilityReply(9001L, request -> {
            calls.add(request);
            return ToolExecutionResult.builder().isError(false)
                    .resultText("{\"orderId\":9001,\"eligible\":false,\"refundExists\":false,"
                            + "\"reason\":\"已超过期限\"}")
                    .build();
        });
        assertEquals("订单 9001 当前不可退：已超过期限。本次未提交退款。", reply);
        assertEquals("get_refund_eligibility", calls.get(1).name());
        assertEquals("{\"orderId\":9001}", calls.get(1).arguments());
        assertTrue(!reply.contains("已退款"), reply);
    }

    @Test
    void malformedQualificationOrOrderListCannotBecomeTrustedReply() {
        assertEquals(List.of(), AgentMain.listedOrderIds(request ->
                ToolExecutionResult.builder().isError(false)
                        .resultText("{\"records\":[{\"id\":0},{\"id\":\"9001\"}]}").build()));
        String wrongOrder = AgentMain.eligibilityReply(9001L, request ->
                ToolExecutionResult.builder().isError(false)
                        .resultText("{\"orderId\":9002,\"eligible\":true,\"refundExists\":false}")
                        .build());
        assertTrue(wrongOrder.contains("无法确认"), wrongOrder);
        assertTrue(!wrongOrder.contains("可申请"), wrongOrder);
    }

    @Test
    void readOnlyRefundStatusRequiresFreshMatchingGetOrderFact() {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        boolean refunded = AgentMain.isOrderRefunded(9001L, request -> {
            calls.add(request);
            return ToolExecutionResult.builder().isError(false)
                    .resultText("{\"id\":9001,\"orderNo\":\"A9001\",\"status\":\"REFUNDED\","
                            + "\"totalAmount\":199.99,\"createdAt\":\"2026-09-26T10:00:00\"}")
                    .build();
        });
        assertTrue(refunded);
        assertEquals("get_order", calls.get(0).name());
        assertEquals("{\"orderId\":9001}", calls.get(0).arguments());

        assertTrue(!AgentMain.isOrderRefunded(9001L, request ->
                ToolExecutionResult.builder().isError(false)
                        .resultText("{\"id\":9001,\"status\":\"RECEIVED\"}").build()));
        assertTrue(!AgentMain.isOrderRefunded(9001L, request ->
                ToolExecutionResult.builder().isError(false)
                        .resultText("{\"id\":9002,\"status\":\"REFUNDED\"}").build()));
        assertTrue(!AgentMain.isOrderRefunded(9001L, request ->
                ToolExecutionResult.builder().isError(true)
                        .resultText("{\"id\":9001,\"status\":\"REFUNDED\"}").build()));
    }

    @Test
    void cliUsesCoordinatorTrustedReply() throws Exception {
        RefundHandoffTools handoff = new RefundHandoffTools();
        AtomicInteger workflowCalls = new AtomicInteger();
        ConversationCoordinator coordinator = new ConversationCoordinator((session, input) -> {
            handoff.handoffRefund(9001L, "不想要了");
            return "退款已完成";
        }, handoff, new EscalationTools("session-1", ignored -> { }),
                () -> List.of(9001L), id -> "未提交退款", id -> false, (session, request) -> {
                    workflowCalls.incrementAndGet();
                    return "不应调用";
                });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        AgentMain.runSession(coordinator, "session-1",
                new BufferedReader(new StringReader("请退订单 9001，理由：不想要了\nexit\n")),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("/confirm-refund 9001"), output);
        assertTrue(!output.contains("退款已完成"), output);
        assertEquals(0, workflowCalls.get());
    }

    @Test
    void unresolvedModelPlaceholdersMustStopStartup() {
        Properties properties = modelProperties();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentMain.modelProperties(properties, Map.of()));

        assertTrue(e.getMessage().contains("model.baseUrl"), e.getMessage());
    }

    @Test
    void environmentModelValuesOverridePlaceholders() {
        ModelProperties model = AgentMain.modelProperties(modelProperties(), Map.of(
                "MODEL_BASE_URL", "https://models.example/v1",
                "MODEL_API_KEY", "test-key",
                "MODEL_NAME", "test-model"));

        assertEquals("https://models.example/v1", model.baseUrl());
        assertEquals("test-model", model.name());
    }

    @Test
    void missingMcpJarMustStopStartupBeforeClientCreation() throws Exception {
        Path emptyRepository = Files.createTempDirectory("after-sales-agent-test");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> AgentMain.mcpServerJar(emptyRepository));
            assertTrue(e.getMessage().contains("mcp-server"), e.getMessage());
        } finally {
            Files.deleteIfExists(emptyRepository);
        }
    }

    @Test
    void backendCredentialsMustStopAgentStartupWithoutEchoingValues() {
        for (String key : new String[]{"MERCHANT_JWT_SECRET",
                "SPRING_DATASOURCE_PASSWORD", "SPRING_RABBITMQ_PASSWORD"}) {
            String secret = "synthetic-secret-should-not-be-printed";
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> AgentMain.rejectBackendCredentials(Map.of(key, secret)));
            assertTrue(e.getMessage().contains(key), e.getMessage());
            assertTrue(!e.getMessage().contains(secret), e.getMessage());
        }
        AgentMain.rejectBackendCredentials(Map.of("MERCHANT_JWT_SECRET", " "));
    }

    private static Properties modelProperties() {
        Properties properties = new Properties();
        properties.setProperty("model.baseUrl", "${MODEL_BASE_URL}");
        properties.setProperty("model.apiKey", "${MODEL_API_KEY}");
        properties.setProperty("model.name", "${MODEL_NAME}");
        properties.setProperty("model.temperature", "0.0");
        return properties;
    }
}
