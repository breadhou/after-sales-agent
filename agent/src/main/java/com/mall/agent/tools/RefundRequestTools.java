package com.mall.agent.tools;

import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 退款申请的复核与执行通路。决策 Agent 只持有本工具，不持有 MCP 的 submit_refund。
 * 复核所需的事实由可信代码重读，不接收决策 Agent 的推理过程。
 */
public class RefundRequestTools {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestTools.class);

    private final BiFunction<Long, String, RefundReviewContext> contextFactory;
    private final Function<RefundReviewContext, ReviewVerdict> reviewer;
    private final BiFunction<Long, String, String> executor;
    private final BiFunction<Long, String, String> escalation;
    private final String sessionId;

    private enum ReviewState { IN_REVIEW, REJECTED }

    /** 每个会话独有的工具实例；同订单复核中只允许一次请求，驳回状态保留到会话结束。 */
    private final ConcurrentMap<Long, ReviewState> reviewStates = new ConcurrentHashMap<>();

    public RefundRequestTools(BiFunction<Long, String, RefundReviewContext> contextFactory,
                              Function<RefundReviewContext, ReviewVerdict> reviewer,
                              BiFunction<Long, String, String> executor,
                              BiFunction<Long, String, String> escalation,
                              String sessionId) {
        this.contextFactory = contextFactory;
        this.reviewer = reviewer;
        this.executor = executor;
        this.escalation = escalation;
        this.sessionId = sessionId;
    }

    @Tool(name = "request_refund", value = """
         提交退款申请。系统会对该申请进行合规复核，复核通过后才会真正执行；
         复核未通过会记录人工升级请求，并引导用户联系人工客服。调用前你必须已经用 get_refund_eligibility
         确认过该订单符合退款条件。""")
    public String requestRefund(@P(name = "orderId", value = "订单 ID") Long orderId,
                                @P(name = "reason", value = "退款原因，来自用户的说明") String reason) {
        ReviewState previous = reviewStates.putIfAbsent(orderId, ReviewState.IN_REVIEW);
        if (previous == ReviewState.REJECTED) {
            return "该订单在本次会话中已记录人工升级请求。请引导用户联系人工客服继续处理。";
        }
        if (previous == ReviewState.IN_REVIEW) {
            return "该订单正在复核，本次重复请求未提交。";
        }

        try {
            ReviewVerdict verdict;
            try {
                RefundReviewContext context = contextFactory.apply(orderId, reason);
                if (context == null) {
                    throw new IllegalStateException("复核上下文缺失");
                }
                verdict = reviewer.apply(context);
                if (verdict == null) {
                    verdict = ReviewVerdict.rejected("复核未返回结论");
                }
            } catch (Exception e) {
                log.error("退款复核未完成，按驳回处理 session={} orderId={}", sessionId, orderId, e);
                verdict = ReviewVerdict.rejected("复核未完成");
            }

            if (!verdict.approved()) {
                // 先终止该订单，再升级；重入请求无法在升级过程中重新送审。
                reviewStates.replace(orderId, ReviewState.IN_REVIEW, ReviewState.REJECTED);
                log.warn("退款复核驳回 session={} orderId={} faults={}", sessionId, orderId, verdict.faults());
                escalation.apply(orderId, "退款复核未通过");
                return "该退款申请未通过合规复核，已记录人工升级请求。"
                        + "请引导用户联系人工客服继续处理，不要承诺退款一定成功。";
            }

            log.info("退款复核通过 session={} orderId={}", sessionId, orderId);
            try {
                return executor.apply(orderId, reason);
            } catch (RuntimeException e) {
                // MCP 错误或超时可能发生在后端写入之后，只能说结果不确定。
                log.error("退款提交结果无法确认 session={} orderId={}", sessionId, orderId, e);
                return "该退款申请的提交结果无法确认。请引导用户联系人工客服核实退款状态。";
            }
        } finally {
            // 通过或执行不确定时释放进行中标记；已驳回状态不会被清除。
            reviewStates.remove(orderId, ReviewState.IN_REVIEW);
        }
    }
}
