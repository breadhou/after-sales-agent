package com.mall.agent.tools;

import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.trace.ToolTrace;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 决策模型只能留下无写入的转接或资格询问标记。 */
public final class RefundHandoffTools {

    private record EligibilityQuestion(Long orderId) { }

    public record Signals(List<CandidateRefundAction> handoffs, List<Long> eligibilityQuestions) { }

    private final ConcurrentLinkedQueue<CandidateRefundAction> handoffs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<EligibilityQuestion> eligibilityQuestions = new ConcurrentLinkedQueue<>();

    @Tool(name = "handoff_refund", value = "仅记录退款转接候选，不查询资格、不提交退款。必须由用户随后选择订单并确认。")
    public String handoffRefund(@P(name = "orderId", value = "候选订单 ID；不明确时传 0") Long orderId,
                                @P(name = "reason", value = "用户陈述的退款理由") String reason) {
        ToolTrace.record("handoff_refund", ToolTrace.Status.CALLED);
        handoffs.add(new CandidateRefundAction(orderId, reason));
        ToolTrace.record("handoff_refund", ToolTrace.Status.OK);
        return "已记录退款转接候选，等待用户确认；本次未提交退款。";
    }

    @Tool(name = "ask_refund_eligibility", value = "仅记录用户对退款资格的询问，不读取资格、不提交退款。")
    public String askRefundEligibility(@P(name = "orderId", value = "候选订单 ID；不明确时传 0") Long orderId) {
        ToolTrace.record("ask_refund_eligibility", ToolTrace.Status.CALLED);
        eligibilityQuestions.add(new EligibilityQuestion(orderId == null ? 0L : orderId));
        ToolTrace.record("ask_refund_eligibility", ToolTrace.Status.OK);
        return "已记录退款资格询问，等待可信代码核对；本次未提交退款。";
    }

    /** 每轮处理前清除迟到标记，避免跨轮复用模型候选。 */
    public void clear() {
        handoffs.clear();
        eligibilityQuestions.clear();
    }

    /** 一次取尽本轮标记；协调器按升级、转接、资格的顺序决定可信答复。 */
    public Signals takeSignals() {
        List<CandidateRefundAction> currentHandoffs = new ArrayList<>();
        CandidateRefundAction handoff;
        while ((handoff = handoffs.poll()) != null) {
            currentHandoffs.add(handoff);
        }
        List<Long> currentQuestions = new ArrayList<>();
        EligibilityQuestion question;
        while ((question = eligibilityQuestions.poll()) != null) {
            currentQuestions.add(question.orderId());
        }
        return new Signals(List.copyOf(currentHandoffs), List.copyOf(currentQuestions));
    }
}
