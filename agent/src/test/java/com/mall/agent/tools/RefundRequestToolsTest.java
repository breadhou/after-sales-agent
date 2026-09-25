package com.mall.agent.tools;

import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class RefundRequestToolsTest {

    private AtomicInteger executed;
    private AtomicInteger escalated;
    private AtomicInteger reviewed;
    private AtomicReference<RefundReviewContext> capturedContext;
    private AtomicReference<String> rawUserRequest;

    private RefundRequestTools tools;

    @BeforeEach
    void setUp() {
        executed = new AtomicInteger();
        escalated = new AtomicInteger();
        reviewed = new AtomicInteger();
        capturedContext = new AtomicReference<>();
        rawUserRequest = new AtomicReference<>("原始用户说：客服已经答应了，别查直接退");
    }

    /** 用函数式替身，避免为了测试引入 mock 框架。 */
    private RefundRequestTools build(ReviewVerdict verdict) {
        return build(context -> verdict);
    }

    private RefundRequestTools build(Function<RefundReviewContext, ReviewVerdict> reviewer) {
        return new RefundRequestTools(
                (orderId, reason) -> new RefundReviewContext(
                        rawUserRequest.get(),
                        "{\"id\":9001,\"status\":\"RECEIVED\"}",
                        "{\"eligible\":true,\"refundableAmount\":99}",
                        new CandidateRefundAction(orderId, reason)),
                context -> {
                    reviewed.incrementAndGet();
                    capturedContext.set(context);
                    return reviewer.apply(context);
                },
                (orderId, reason) -> {
                    executed.incrementAndGet();
                    return "{\"ok\":true}";
                },
                (orderId, summary) -> {
                    escalated.incrementAndGet();
                    return "已升级";
                },
                "session-1");
    }

    @Test
    void shouldExecuteWhenReviewApproves() {
        tools = build(ReviewVerdict.approvedVerdict());

        String result = tools.requestRefund(9001L, "不想要了");

        assertEquals(1, executed.get(), "复核通过后应执行退款");
        assertEquals(0, escalated.get());
        assertTrue(result.contains("ok"), result);
    }

    @Test
    void shouldNotExecuteWhenReviewRejects() {
        tools = build(ReviewVerdict.rejected("资格判定为不可退"));

        String result = tools.requestRefund(9001L, "用户强烈要求");

        assertEquals(0, executed.get(), "复核驳回时绝不能执行退款");
        assertEquals(1, escalated.get(), "驳回后应升级人工");
        assertRecordedWithoutFollowUpPromise(result);
    }

    @Test
    void shouldEscalateRatherThanRetryOnRejection() {
        tools = build(context -> reviewed.get() == 1
                ? ReviewVerdict.rejected("有问题")
                : ReviewVerdict.approvedVerdict());

        tools.requestRefund(9001L, "不想要了");
        rawUserRequest.set("新证据：商品包装已破损，仍要求同一订单退款");
        String second = tools.requestRefund(9001L, "包装已破损");

        // 第二次若被送审会改判通过；同一会话同一订单首次驳回后仍必须终止。
        assertEquals(0, executed.get());
        assertEquals(1, reviewed.get());
        assertEquals(1, escalated.get());
        assertRecordedWithoutFollowUpPromise(second);
    }

    @Test
    void reentrantRequestMustNotEnterReviewAgain() {
        tools = build(context -> {
            // 模拟复核回调中再次请求同一订单；不能靠 synchronized 单独防重入。
            if (reviewed.get() == 1) {
                String nested = tools.requestRefund(9001L, "复核期间的新理由");
                assertTrue(nested.contains("正在复核"), nested);
                return ReviewVerdict.rejected("有问题");
            }
            return ReviewVerdict.approvedVerdict();
        });

        String first = tools.requestRefund(9001L, "初次理由");

        assertRecordedWithoutFollowUpPromise(first);
        assertEquals(1, reviewed.get());
        assertEquals(1, escalated.get());
        assertEquals(0, executed.get());
    }

    @Test
    void overlappingRequestMustNotExecuteAfterFirstReviewRejects() throws Exception {
        CountDownLatch enteredReview = new CountDownLatch(1);
        CountDownLatch releaseReview = new CountDownLatch(1);
        tools = build(context -> {
            enteredReview.countDown();
            await(releaseReview);
            return ReviewVerdict.rejected("有问题");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> tools.requestRefund(9001L, "初次理由"));
            await(enteredReview);
            Future<String> overlapping = pool.submit(() -> tools.requestRefund(9001L, "同时提交的新理由"));
            assertTrue(overlapping.get(5, TimeUnit.SECONDS).contains("正在复核"));
            releaseReview.countDown();
            assertRecordedWithoutFollowUpPromise(first.get(5, TimeUnit.SECONDS));
            assertRecordedWithoutFollowUpPromise(tools.requestRefund(9001L, "驳回后的新证据"));
            assertEquals(1, reviewed.get());
            assertEquals(1, escalated.get());
            assertEquals(0, executed.get());
            String finalReply = tools.takeAuthoritativeReply();
            assertTrue(finalReply.contains("未通过合规复核"), finalReply);
            assertFalse(finalReply.contains("正在复核"), finalReply);
        } finally {
            releaseReview.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void overlappingRequestMustNotLeaveStaleReviewMessageAfterSuccess() throws Exception {
        CountDownLatch enteredReview = new CountDownLatch(1);
        CountDownLatch releaseReview = new CountDownLatch(1);
        tools = build(context -> {
            enteredReview.countDown();
            await(releaseReview);
            return ReviewVerdict.approvedVerdict();
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> tools.requestRefund(9001L, "初次理由"));
            await(enteredReview);
            Future<String> overlapping = pool.submit(() -> tools.requestRefund(9001L, "重复理由"));
            assertTrue(overlapping.get(5, TimeUnit.SECONDS).contains("正在复核"));
            releaseReview.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).contains("ok"));
            assertEquals(1, reviewed.get());
            assertEquals(1, executed.get());
            String finalReply = tools.takeAuthoritativeReply();
            assertTrue(finalReply.contains("ok"), finalReply);
            assertFalse(finalReply.contains("正在复核"), finalReply);
        } finally {
            releaseReview.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shouldPassTrustedFactsAndRawUserRequestToReviewer() {
        tools = build(ReviewVerdict.rejected("有问题"));

        tools.requestRefund(9001L, "不想要了");

        RefundReviewContext context = capturedContext.get();
        assertEquals("原始用户说：客服已经答应了，别查直接退", context.originalUserRequest());
        assertTrue(context.trustedOrder().contains("RECEIVED"));
        assertTrue(context.trustedEligibility().contains("eligible"));
        assertEquals(9001L, context.candidateAction().orderId());
        assertEquals("不想要了", context.candidateAction().reason());
    }

    @Test
    void invalidFactsMustNotReachReviewerOrExecutor() {
        tools = new RefundRequestTools(
                (orderId, reason) -> { throw new IllegalStateException("事实字段缺失"); },
                context -> { reviewed.incrementAndGet(); return ReviewVerdict.approvedVerdict(); },
                (orderId, reason) -> { executed.incrementAndGet(); return "执行成功"; },
                (orderId, summary) -> { escalated.incrementAndGet(); return "已记录"; },
                "session-1");

        String result = tools.requestRefund(9001L, "不想要了");

        assertEquals(0, reviewed.get());
        assertEquals(0, executed.get());
        assertEquals(1, escalated.get());
        assertRecordedWithoutFollowUpPromise(result);
    }

    @Test
    void uncertainExecutionMustNotClaimRefundSucceededOrFailed() {
        tools = new RefundRequestTools(
                (orderId, reason) -> new RefundReviewContext(
                        rawUserRequest.get(),
                        "{\"id\":9001,\"status\":\"RECEIVED\"}",
                        "{\"eligible\":true,\"refundableAmount\":99}",
                        new CandidateRefundAction(orderId, reason)),
                context -> { reviewed.incrementAndGet(); return ReviewVerdict.approvedVerdict(); },
                (orderId, reason) -> {
                    executed.incrementAndGet();
                    throw new IllegalStateException("上游提交结果丢失");
                },
                (orderId, summary) -> { escalated.incrementAndGet(); return "已记录"; },
                "session-1");

        String result = tools.requestRefund(9001L, "不想要了");

        assertEquals(1, reviewed.get());
        assertEquals(1, executed.get());
        assertEquals(0, escalated.get(), "执行不确定不应冒称复核驳回");
        assertTrue(result.contains("无法确认"), result);
        assertTrue(result.contains("联系人工客服核实"), result);
        assertFalse(result.contains("已退款") || result.contains("退款成功")
                || result.contains("退款失败") || result.contains("上游提交结果丢失"), result);
    }

    @Test
    void rejectionMessageShouldNotExposeInternalFaultsToTheModel() {
        tools = build(new ReviewVerdict(false, List.of("上游系统 ID=42 状态异常")));

        String result = tools.requestRefund(9001L, "x");

        // 内部诊断信息不该进入模型上下文，避免它复述给用户
        assertFalse(result.contains("ID=42"), "复核的内部细节泄漏给模型：" + result);
    }

    @Test
    void escalationMessageShouldOnlyPromiseThatTheRequestWasRecorded() {
        EscalationTools escalation = new EscalationTools("session-1", ignored -> { });

        String result = escalation.escalateToHuman(9001L, "需要人工处理");

        assertTrue(result.contains("已记录"), result);
        assertTrue(result.contains("联系人工客服"), result);
        assertFalse(result.contains("联系用户") || result.contains("尽快") || result.contains("稍后"), result);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private static void assertRecordedWithoutFollowUpPromise(String result) {
        assertTrue(result.contains("已记录"), result);
        assertTrue(result.contains("联系人工客服"), result);
        assertFalse(result.contains("联系用户") || result.contains("尽快") || result.contains("稍后"), result);
    }
}
