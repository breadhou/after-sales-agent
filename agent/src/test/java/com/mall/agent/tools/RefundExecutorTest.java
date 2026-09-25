package com.mall.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RefundExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void callsOnlySubmitRefundWithOrderIdAndReason() throws Exception {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        RefundExecutor executor = new RefundExecutor(request -> {
            calls.add(request);
            return ToolExecutionResult.builder()
                    .resultText("{\"orderId\":9001,\"refundExists\":false,"
                            + "\"eligible\":true,\"refundableAmount\":199.99}")
                    .isError(false).build();
        });

        String result = executor.apply(9001L, "不想要了");

        assertTrue(result.contains("订单 9001 的退款已完成"), result);
        assertTrue(result.contains("199.99 元"), result);
        assertTrue(result.contains("REFUNDED"), result);
        assertFalse(result.contains("复核通过后") || result.contains("待执行"), result);
        assertEquals(1, calls.size());
        assertEquals("submit_refund", calls.get(0).name());
        JsonNode arguments = MAPPER.readTree(calls.get(0).arguments());
        assertEquals(2, arguments.size(), "调用方不能指定退款金额");
        assertEquals(9001L, arguments.path("orderId").longValue());
        assertEquals("不想要了", arguments.path("reason").textValue());
    }

    @Test
    void existingPendingRefundMustNotBeCalledCompleted() {
        RefundExecutor executor = new RefundExecutor(request -> ToolExecutionResult.builder()
                .resultText("{\"orderId\":9001,\"refundExists\":true,\"eligible\":false,"
                        + "\"refundableAmount\":199.99,\"reason\":\"该订单已有退款申请在处理中\"}")
                .isError(false).build());

        String result = executor.apply(9001L, "不想要了");

        assertTrue(result.contains("在处理中"), result);
        assertFalse(result.contains("已完成退款") || result.contains("REFUNDED"), result);
    }

    @Test
    void existingCompletedRefundMustNotBeCalledNewExecution() {
        RefundExecutor executor = new RefundExecutor(request -> ToolExecutionResult.builder()
                .resultText("{\"orderId\":9001,\"refundExists\":true,\"eligible\":false,"
                        + "\"refundableAmount\":199.99,\"reason\":\"该订单已完成退款\"}")
                .isError(false).build());

        String result = executor.apply(9001L, "不想要了");

        assertTrue(result.contains("此前已完成退款"), result);
        assertTrue(result.contains("199.99 元"), result);
        assertFalse(result.contains("本次执行"), result);
    }

    @Test
    void mismatchedOrUnknownSuccessReceiptMustRemainUncertain() {
        for (String text : new String[]{
                "{\"orderId\":9002,\"refundExists\":false,\"eligible\":true,\"refundableAmount\":199.99}",
                "{\"orderId\":9001,\"refundExists\":false,\"eligible\":true}",
                "{\"orderId\":9001,\"refundExists\":true,\"eligible\":false,\"refundableAmount\":199.99,\"reason\":\"未知状态\"}",
                "accepted"}) {
            RefundExecutor executor = new RefundExecutor(request -> ToolExecutionResult.builder()
                    .resultText(text).isError(false).build());
            assertThrows(IllegalStateException.class, () -> executor.apply(9001L, "不想要了"), text);
        }
    }

    @Test
    void rejectsErrorResultEvenWhenItContainsText() {
        RefundExecutor executor = new RefundExecutor(request -> ToolExecutionResult.builder()
                .resultText("{\"error\":true,\"message\":\"退款失败\"}")
                .isError(true).build());

        assertThrows(IllegalStateException.class, () -> executor.apply(9001L, "不想要了"));
    }

    @Test
    void rejectsMissingOrBlankResult() {
        assertThrows(IllegalStateException.class,
                () -> new RefundExecutor(request -> null).apply(9001L, "不想要了"));
        // SDK 不允许直接用 resultText(null) 构造结果；惰性空文本同样不能作为成功回执。
        assertThrows(IllegalStateException.class, () -> new RefundExecutor(request ->
                ToolExecutionResult.builder().resultTextSupplier(() -> null).build())
                .apply(9001L, "不想要了"));
        for (String text : new String[]{"", "   "}) {
            RefundExecutor executor = new RefundExecutor(request -> ToolExecutionResult.builder()
                    .resultText(text).isError(false).build());
            assertThrows(IllegalStateException.class, () -> executor.apply(9001L, "不想要了"));
        }
    }

    @Test
    void propagatesToolCallExceptionForTheRequestToolToHandle() {
        RefundExecutor executor = new RefundExecutor(request -> {
            throw new IllegalStateException("MCP 调用失败");
        });

        assertThrows(IllegalStateException.class, () -> executor.apply(9001L, "不想要了"));
    }
}
