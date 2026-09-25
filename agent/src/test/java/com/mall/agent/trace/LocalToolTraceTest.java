package com.mall.agent.trace;

import com.mall.agent.config.AgentConfig;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.RefundRequestTools;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalToolTraceTest {

    @Test
    void rejectedRefundRecordsReviewAndEscalationWithoutUserText() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            EscalationTools escalation = new EscalationTools("session", ignored -> { });
            RefundRequestTools tools = new RefundRequestTools(
                    (id, reason) -> new RefundReviewContext("synthetic-user-secret", "{}", "{}",
                            new CandidateRefundAction(id, reason)),
                    context -> ReviewVerdict.rejected("synthetic-review-secret"),
                    (id, reason) -> { throw new AssertionError("must not execute"); },
                    escalation::escalateToHuman, "session");

            tools.requestRefund(1L, "synthetic-reason-secret");

            String trace = captured.toString(StandardCharsets.UTF_8);
            assertEquals("TASK6_TRACE tool=request_refund status=called" + System.lineSeparator()
                            + "TASK6_TRACE tool=review status=called" + System.lineSeparator()
                            + "TASK6_TRACE tool=review status=error" + System.lineSeparator()
                            + "TASK6_TRACE tool=escalate_to_human status=called" + System.lineSeparator()
                            + "TASK6_TRACE tool=escalate_to_human status=ok" + System.lineSeparator()
                            + "TASK6_TRACE tool=request_refund status=error" + System.lineSeparator(),
                    onlyTraceLines(trace));
            assertFalse(trace.contains("synthetic-user-secret"));
            assertFalse(trace.contains("synthetic-reason-secret"));
            assertFalse(trace.contains("synthetic-review-secret"));
        } finally {
            System.setErr(old);
        }
    }

    @Test
    void modelExceptionEmitsTransportErrorBeforeFinalRejection() {
        String trace = runRefund((id, reason) -> reviewContext(id, reason),
                context -> AgentConfig.reviewSafely(message -> {
                    throw new IllegalStateException("synthetic-model-secret");
                }, context));

        assertTrue(trace.contains("TASK6_TRACE tool=review status=transport_error"));
        assertTrue(trace.contains("TASK6_TRACE tool=review status=error"));
        assertFalse(trace.contains("synthetic-model-secret"));
    }

    @Test
    void nullModelVerdictEmitsTransportError() {
        String trace = runRefund((id, reason) -> reviewContext(id, reason),
                context -> AgentConfig.reviewSafely(message -> null, context));

        assertTrue(trace.contains("TASK6_TRACE tool=review status=transport_error"));
    }

    @Test
    void factReadFailureEmitsTransportError() {
        String trace = runRefund((id, reason) -> {
                    throw new IllegalStateException("synthetic-fact-secret");
                }, context -> ReviewVerdict.approvedVerdict());

        assertTrue(trace.contains("TASK6_TRACE tool=review status=transport_error"));
        assertFalse(trace.contains("synthetic-fact-secret"));
    }

    @Test
    void directReviewerFailureEmitsTransportError() {
        String trace = runRefund((id, reason) -> reviewContext(id, reason),
                context -> { throw new IllegalStateException("synthetic-reviewer-secret"); });

        assertTrue(trace.contains("TASK6_TRACE tool=review status=transport_error"));
        assertFalse(trace.contains("synthetic-reviewer-secret"));
    }

    private static RefundReviewContext reviewContext(Long id, String reason) {
        return new RefundReviewContext("synthetic-user-secret", "{}", "{}",
                new CandidateRefundAction(id, reason));
    }

    private static String runRefund(
            java.util.function.BiFunction<Long, String, RefundReviewContext> contextFactory,
            java.util.function.Function<RefundReviewContext, ReviewVerdict> reviewer) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            EscalationTools escalation = new EscalationTools("session", ignored -> { });
            RefundRequestTools tools = new RefundRequestTools(contextFactory, reviewer,
                    (id, reason) -> { throw new AssertionError("must not execute"); },
                    escalation::escalateToHuman, "session");
            tools.requestRefund(1L, "synthetic-reason-secret");
            return onlyTraceLines(captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(old);
        }
    }

    private static String onlyTraceLines(String captured) {
        return captured.lines().filter(line -> line.startsWith("TASK6_TRACE "))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
    }
}
