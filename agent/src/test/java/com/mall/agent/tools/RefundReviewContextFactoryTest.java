package com.mall.agent.tools;

import com.mall.agent.model.CheckedRefundFacts;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RefundReviewContextFactoryTest {

    private static final String ORDER =
            "{\"id\":9001,\"status\":\"RECEIVED\",\"totalAmount\":99.00}";
    private static final String ELIGIBILITY =
            "{\"orderId\":9001,\"eligible\":true,\"refundExists\":false,"
            + "\"refundableAmount\":99.00,\"policyCode\":\"SEVEN_DAY_NO_REASON\","
            + "\"policyTitle\":\"已签收（完整天数不超过 7）整单退款\"}";
    private static final String FINGERPRINT = "0123456789abcdef".repeat(4);
    private static final String CHECKED_ELIGIBILITY =
            "{\"orderId\":9001,\"eligible\":true,\"reason\":null,\"refundExists\":false,"
            + "\"refundableAmount\":99.00,\"policyCode\":\"SEVEN_DAY_NO_REASON\","
            + "\"policyTitle\":\"已签收（完整天数不超过 7）整单退款\","
            + "\"catalogFingerprint\":\"" + FINGERPRINT + "\",\"orderStatus\":\"RECEIVED\"}";

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

    @Test
    void eligibleFactsContainVerifiedValuesAndOriginalInput() {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        CheckedRefundFacts facts = factory(ORDER.replace("99.00", "99.0"), CHECKED_ELIGIBILITY, false, calls)
                .requireEligible(9001L, "不想要了", "本轮原话：申请 9001 退款");

        assertEquals(List.of("get_order", "get_refund_eligibility"),
                calls.stream().map(ToolExecutionRequest::name).toList());
        assertTrue(calls.stream().allMatch(call -> call.arguments().equals("{\"orderId\":9001}")));
        assertEquals("本轮原话：申请 9001 退款", facts.originalUserRequest());
        assertEquals(9001L, facts.candidateAction().orderId());
        assertEquals("不想要了", facts.candidateAction().reason());
        assertEquals("SEVEN_DAY_NO_REASON", facts.policyCode());
        assertEquals(FINGERPRINT, facts.catalogFingerprint());
        assertEquals(0, facts.refundableAmount().compareTo(new BigDecimal("99.00")));
        assertTrue(facts.trustedOrder().contains("\"status\":\"RECEIVED\""));
        assertTrue(facts.trustedEligibility().contains("\"orderStatus\":\"RECEIVED\""));
    }

    @Test
    void rejectsIneligibleBeforeReviewer() {
        String denial = CHECKED_ELIGIBILITY.replace("\"eligible\":true", "\"eligible\":false")
                .replace("\"reason\":null", "\"reason\":\"订单当前不可退\"")
                .replace("\"policyCode\":\"SEVEN_DAY_NO_REASON\"", "\"policyCode\":null")
                .replace("\"policyTitle\":\"已签收（完整天数不超过 7）整单退款\"", "\"policyTitle\":null");
        RefundReviewContextFactory factory = factory(ORDER, denial, false, new ArrayList<>());

        var rejected = assertThrows(RefundReviewContextFactory.RefundNotEligibleException.class,
                () -> factory.requireEligible(9001L, "退款", "原话"));
        assertEquals("订单当前不可退", rejected.getMessage());
        assertNoReviewOrExecution(factory);
    }

    @Test
    void rejectsExistingRefundBeforeReviewer() {
        String denial = CHECKED_ELIGIBILITY.replace("\"eligible\":true", "\"eligible\":false")
                .replace("\"refundExists\":false", "\"refundExists\":true")
                .replace("\"reason\":null", "\"reason\":\"该订单已有退款申请在处理中\"")
                .replace("\"policyCode\":\"SEVEN_DAY_NO_REASON\"", "\"policyCode\":null")
                .replace("\"policyTitle\":\"已签收（完整天数不超过 7）整单退款\"", "\"policyTitle\":null");
        RefundReviewContextFactory factory = factory(ORDER, denial, false, new ArrayList<>());

        var rejected = assertThrows(RefundReviewContextFactory.RefundNotEligibleException.class,
                () -> factory.requireEligible(9001L, "退款", "原话"));
        assertEquals("该订单已有退款申请在处理中", rejected.getMessage());
        assertNoReviewOrExecution(factory);
        assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
                () -> factory(ORDER, CHECKED_ELIGIBILITY.replace("\"refundExists\":false", "\"refundExists\":true"),
                        false, new ArrayList<>()).requireEligible(9001L, "退款", "原话"));
    }

    @Test
    void deniedEligibilityWithMismatchedAmountIsUnavailable() {
        String denial = CHECKED_ELIGIBILITY.replace("\"eligible\":true", "\"eligible\":false")
                .replace("\"reason\":null", "\"reason\":\"订单当前不可退\"")
                .replace("\"refundableAmount\":99.00", "\"refundableAmount\":98.00")
                .replace("\"policyCode\":\"SEVEN_DAY_NO_REASON\"", "\"policyCode\":null")
                .replace("\"policyTitle\":\"已签收（完整天数不超过 7）整单退款\"", "\"policyTitle\":null");
        RefundReviewContextFactory factory = factory(ORDER, denial, false, new ArrayList<>());

        assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
                () -> factory.requireEligible(9001L, "退款", "原话"));
        assertNoReviewOrExecution(factory);
    }

    @Test
    void rejectsMismatchedAmountOrOrderStatus() {
        for (String eligibility : List.of(
                CHECKED_ELIGIBILITY.replace("\"refundableAmount\":99.00", "\"refundableAmount\":98.00"),
                CHECKED_ELIGIBILITY.replace("\"refundableAmount\":99.00", "\"refundableAmount\":0"),
                CHECKED_ELIGIBILITY.replace("\"refundableAmount\":99.00", "\"refundableAmount\":-1"),
                CHECKED_ELIGIBILITY.replace("\"refundableAmount\":99.00", "\"refundableAmount\":\"99.00\""),
                CHECKED_ELIGIBILITY.replace("\"orderStatus\":\"RECEIVED\"", "\"orderStatus\":\"SHIPPED\""))) {
            RefundReviewContextFactory factory = factory(ORDER, eligibility, false, new ArrayList<>());
            assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
                    () -> factory.requireEligible(9001L, "退款", "原话"));
            assertNoReviewOrExecution(factory);
        }
    }

    @Test
    void rejectsStatusChangeBetweenOrderAndEligibilityReads() {
        String shippedOrder = ORDER.replace("RECEIVED", "SHIPPED");
        String deliveredEligibility = CHECKED_ELIGIBILITY
                .replace("RECEIVED", "DELIVERED")
                .replace("SEVEN_DAY_NO_REASON", "SHIPPED_NOT_RECEIVED");
        RefundReviewContextFactory factory = factory(shippedOrder, deliveredEligibility, false, new ArrayList<>());

        assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
                () -> factory.requireEligible(9001L, "退款", "原话"));
        assertNoReviewOrExecution(factory);
    }

    @Test
    void rejectsWrongOrderIdAndMalformedFacts() {
        for (String order : List.of(ORDER.replace("9001", "9002"), ORDER + " {}", "not JSON")) {
            assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
                    () -> factory(order, CHECKED_ELIGIBILITY, false, new ArrayList<>())
                            .requireEligible(9001L, "退款", "原话"));
        }
        for (String eligibility : List.of(
                CHECKED_ELIGIBILITY.replace("\"orderId\":9001", "\"orderId\":9002"),
                CHECKED_ELIGIBILITY + " {}",
                CHECKED_ELIGIBILITY.replace("\"catalogFingerprint\":\"" + FINGERPRINT + "\",", ""),
                CHECKED_ELIGIBILITY.replace("\"orderStatus\":\"RECEIVED\"", "\"orderStatus\":7"),
                CHECKED_ELIGIBILITY.replace("\"eligible\":true", "\"eligible\":\"true\""))) {
            assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
                    () -> factory(ORDER, eligibility, false, new ArrayList<>())
                            .requireEligible(9001L, "退款", "原话"));
        }
        assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
                () -> factory(ORDER, CHECKED_ELIGIBILITY, true, new ArrayList<>())
                        .requireEligible(9001L, "退款", "原话"));
    }

    private static void assertNoReviewOrExecution(RefundReviewContextFactory factory) {
        AtomicInteger reviews = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        RefundRequestTools tools = new RefundRequestTools(
                (orderId, reason) -> {
                    CheckedRefundFacts facts = factory.requireEligible(orderId, reason, "原话");
                    return new RefundReviewContext(facts.originalUserRequest(), facts.trustedOrder(),
                            facts.trustedEligibility(), facts.candidateAction());
                },
                context -> { reviews.incrementAndGet(); return ReviewVerdict.approvedVerdict(); },
                (orderId, reason) -> { executions.incrementAndGet(); return "执行成功"; },
                (orderId, reason) -> "已记录",
                "session-1");
        tools.requestRefund(9001L, "退款");
        assertEquals(0, reviews.get());
        assertEquals(0, executions.get());
    }
}
