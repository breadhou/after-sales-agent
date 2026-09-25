package com.mall.agent;

import com.mall.agent.config.ModelProperties;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import com.mall.agent.tools.RefundRequestTools;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMainTest {

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

    @Test
    void confirmedExecutionMustOverrideModelsPendingClaim() {
        RefundRequestTools refund = refundTools(ReviewVerdict.approvedVerdict(),
                "订单 9001 的退款已完成，退款金额 199.99 元，订单状态 REFUNDED。");
        refund.requestRefund(9001L, "不想要了");

        String answer = AgentMain.finalResponse(
                "申请已提交，系统会复核，复核通过后才会执行退款。", refund);

        assertTrue(answer.contains("已完成"), answer);
        assertTrue(answer.contains("199.99 元"), answer);
        assertTrue(answer.contains("REFUNDED"), answer);
        assertTrue(!answer.contains("复核通过后"), answer);
    }

    @Test
    void uncertainExecutionMustOverrideModelsSuccessClaim() {
        RefundRequestTools uncertain = new RefundRequestTools(
                (orderId, reason) -> context(orderId, reason),
                ignored -> ReviewVerdict.approvedVerdict(),
                (orderId, reason) -> { throw new IllegalStateException("结果丢失"); },
                (orderId, summary) -> "已记录", "test-session");
        uncertain.requestRefund(9001L, "不想要了");

        String answer = AgentMain.finalResponse("退款已经成功。", uncertain);

        assertTrue(answer.contains("无法确认"), answer);
        assertTrue(!answer.contains("退款已经成功"), answer);
    }

    @Test
    void completedRefundMustRemainVisibleWhenLaterOrderIsRejected() {
        RefundRequestTools refund = multiOrderTools(
                orderId -> orderId == 9001L ? ReviewVerdict.approvedVerdict()
                        : ReviewVerdict.rejected("须人工处理"),
                (orderId, reason) -> "订单 " + orderId + " 的退款已完成，退款金额 199.99 元。");
        refund.requestRefund(9001L, "正常退款");
        refund.requestRefund(9002L, "另一个订单");
        refund.requestRefund(9002L, "重复申请");

        String answer = AgentMain.finalResponse("两笔均待处理。", refund);

        assertTrue(answer.contains("订单 9001 的退款已完成"), answer);
        assertTrue(answer.contains("订单 9002"), answer);
        assertTrue(answer.contains("未通过合规复核"), answer);
        assertTrue(answer.contains("本次申请未执行退款"), answer);
        assertTrue(answer.indexOf("订单 9001") < answer.indexOf("订单 9002"), answer);
        assertTrue(!answer.contains("请引导用户") && !answer.contains("不要承诺"), answer);
    }

    @Test
    void completedRefundMustRemainVisibleWhenLaterOrderIsUncertainOrRetried() {
        RefundRequestTools refund = multiOrderTools(
                ignored -> ReviewVerdict.approvedVerdict(),
                (orderId, reason) -> {
                    if (orderId == 9002L) { throw new IllegalStateException("结果丢失"); }
                    return "订单 " + orderId + " 的退款已完成，退款金额 199.99 元。";
                });
        refund.requestRefund(9001L, "正常退款");
        refund.requestRefund(9002L, "另一个订单");

        String answer = AgentMain.finalResponse("两笔均成功。", refund);

        assertTrue(answer.contains("订单 9001 的退款已完成"), answer);
        assertTrue(answer.contains("订单 9002"), answer);
        assertTrue(answer.contains("无法确认"), answer);
        assertTrue(answer.indexOf("订单 9001") < answer.indexOf("订单 9002"), answer);
        assertTrue(!answer.contains("两笔均成功"), answer);
    }

    @Test
    void noRefundCallUsesModelAnswerAndPreviousTurnIsCleared() {
        RefundRequestTools refund = refundTools(ReviewVerdict.approvedVerdict(),
                "订单 9001 的退款已完成，退款金额 199.99 元。");
        assertEquals("只查询订单。", AgentMain.finalResponse("只查询订单。", refund));

        refund.requestRefund(9001L, "正常退款");
        assertTrue(AgentMain.finalResponse("待处理。", refund).contains("订单 9001 的退款已完成"));
        assertEquals("第二轮只查询订单。", AgentMain.finalResponse("第二轮只查询订单。", refund));
    }

    private static RefundRequestTools multiOrderTools(Function<Long, ReviewVerdict> verdict,
                                                      BiFunction<Long, String, String> executor) {
        return new RefundRequestTools(
                (orderId, reason) -> context(orderId, reason),
                review -> verdict.apply(review.candidateAction().orderId()),
                executor,
                (orderId, summary) -> "已记录", "test-session");
    }

    private static RefundRequestTools refundTools(ReviewVerdict verdict, String executionResult) {
        return new RefundRequestTools(
                (orderId, reason) -> context(orderId, reason),
                ignored -> verdict,
                (orderId, reason) -> executionResult,
                (orderId, summary) -> "已记录", "test-session");
    }

    private static RefundReviewContext context(Long orderId, String reason) {
        return new RefundReviewContext("原始诉求", "{\"id\":9001}", "{\"eligible\":true}",
                new CandidateRefundAction(orderId, reason));
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
