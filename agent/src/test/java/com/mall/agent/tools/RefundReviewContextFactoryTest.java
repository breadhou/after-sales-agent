package com.mall.agent.tools;

import com.mall.agent.model.RefundReviewContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RefundReviewContextFactoryTest {

    private static final String ORDER =
            "{\"id\":9001,\"status\":\"RECEIVED\",\"totalAmount\":99.00}";
    private static final String ELIGIBILITY =
            "{\"orderId\":9001,\"eligible\":true,\"refundExists\":false,"
            + "\"refundableAmount\":99.00,\"policyCode\":\"SEVEN_DAY_NO_REASON\","
            + "\"policyTitle\":\"已签收（完整天数不超过 7）整单退款\"}";

    private RefundReviewContextFactory factory(String order, String eligibility,
                                               boolean eligibilityError,
                                               List<ToolExecutionRequest> calls) {
        return new RefundReviewContextFactory(request -> {
            calls.add(request);
            String value = switch (request.name()) {
                case "get_order" -> order;
                case "get_refund_eligibility" -> eligibility;
                default -> throw new AssertionError("不应调用其他 MCP 工具");
            };
            return ToolExecutionResult.builder()
                    .resultText(value)
                    .isError(eligibilityError && "get_refund_eligibility".equals(request.name()))
                    .build();
        }, () -> "用户原话：不想要了，申请退款");
    }

    @Test
    void reReadsBothFactsForTheRequestedOrder() {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        RefundReviewContext context = factory(ORDER, ELIGIBILITY, false, calls)
                .apply(9001L, "不想要了");

        assertEquals(List.of("get_order", "get_refund_eligibility"),
                calls.stream().map(ToolExecutionRequest::name).toList());
        assertTrue(calls.stream().allMatch(call -> call.arguments().contains("\"orderId\":9001")));
        assertEquals("用户原话：不想要了，申请退款", context.originalUserRequest());
        assertEquals(9001L, context.candidateAction().orderId());
        assertTrue(context.trustedOrder().contains("RECEIVED"));
        assertTrue(context.trustedEligibility().contains("SEVEN_DAY_NO_REASON"));
    }

    @Test
    void rejectsMcpBusinessError() {
        assertThrows(IllegalStateException.class, () ->
                factory(ORDER, ELIGIBILITY, true, new ArrayList<>()).apply(9001L, "退款"));
    }

    @Test
    void rejectsMalformedOrIncompleteFacts() {
        assertThrows(IllegalStateException.class, () ->
                factory("not JSON", ELIGIBILITY, false, new ArrayList<>()).apply(9001L, "退款"));
        assertThrows(IllegalStateException.class, () ->
                factory("{}", ELIGIBILITY, false, new ArrayList<>()).apply(9001L, "退款"));
        assertThrows(IllegalStateException.class, () ->
                factory("{\"id\":9001,\"status\":7,\"totalAmount\":\"99\"}",
                        ELIGIBILITY, false, new ArrayList<>()).apply(9001L, "退款"));
        assertThrows(IllegalStateException.class, () ->
                factory(ORDER, "{\"orderId\":9001}", false, new ArrayList<>())
                        .apply(9001L, "退款"));
        assertThrows(IllegalStateException.class, () ->
                factory(ORDER, "{\"orderId\":9001,\"eligible\":true,"
                        + "\"refundExists\":false,\"refundableAmount\":99}",
                        false, new ArrayList<>()).apply(9001L, "退款"));
    }

    @Test
    void rejectsFactsAboutAnotherOrder() {
        assertThrows(IllegalStateException.class, () ->
                factory(ORDER.replace("9001", "9002"), ELIGIBILITY, false, new ArrayList<>())
                        .apply(9001L, "退款"));
        assertThrows(IllegalStateException.class, () ->
                factory(ORDER, ELIGIBILITY.replace("9001", "9002"), false, new ArrayList<>())
                        .apply(9001L, "退款"));
    }

    @Test
    void rejectsMissingOriginalUserRequestBeforeCallingMcp() {
        for (String originalRequest : new String[]{null, "", "  \t\n"}) {
            List<ToolExecutionRequest> calls = new ArrayList<>();
            RefundReviewContextFactory factory = new RefundReviewContextFactory(request -> {
                calls.add(request);
                return ToolExecutionResult.builder().resultText(ORDER).isError(false).build();
            }, () -> originalRequest);

            assertThrows(IllegalStateException.class,
                    () -> factory.apply(9001L, "退款"), "原始诉求：" + originalRequest);
            assertTrue(calls.isEmpty(), "缺少原始诉求时不得调用 MCP 工具");
        }
    }
}
