package com.mall.agent.flow;

import com.mall.agent.model.CheckedRefundFacts;
import com.mall.agent.model.RefundRequest;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewFault;
import com.mall.agent.model.ReviewVerdict;
import com.mall.agent.policy.CatalogSnapshot;
import com.mall.agent.policy.PolicyCatalogConsumer;
import com.mall.agent.policy.PolicyEvidence;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.RefundExecutor;
import com.mall.agent.tools.RefundReviewContextFactory;
import com.mall.agent.trace.ToolTrace;
import dev.langchain4j.mcp.client.McpClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Trusted refund path after a user-confirmed request. The reviewer has no tool access. */
public final class RefundWorkflow {
    /** Recognizes explicit role/instruction framing in untrusted catalog text. */
    private static final Pattern POLICY_ROLE_INSTRUCTION = Pattern.compile(
            "(?im)^\\s*(?:#{1,6}\\s*)?(?:\\[\\s*)?(?:system|developer|assistant|系统指令|开发者指令)"
                    + "(?:\\s*\\])?\\s*[:：]|<\\|im_start\\|>\\s*(?:system|developer|assistant)");
    private static final String GENERIC_REASON = "需要人工核实。";
    @FunctionalInterface
    public interface RefundSubmitter {
        String apply(Long orderId, String reason, String fingerprint, String policyCode);
    }

    private enum State { IN_REVIEW, REJECTED }
    private record Key(String sessionId, Long orderId) { }

    private final RefundReviewContextFactory factsFactory;
    private final PolicyCatalogConsumer catalog;
    private final Function<RefundReviewContext, ReviewVerdict> reviewer;
    private final RefundSubmitter executor;
    private final EscalationTools escalation;
    private final FlowObserver observer;
    private final ConcurrentMap<Key, State> states = new ConcurrentHashMap<>();

    public RefundWorkflow(McpClient mcp, Function<RefundReviewContext, ReviewVerdict> reviewer,
                          RefundSubmitter executor, EscalationTools escalation) {
        this(mcp, reviewer, executor, escalation, FlowObserver.NOOP);
    }

    public RefundWorkflow(McpClient mcp, Function<RefundReviewContext, ReviewVerdict> reviewer,
                          RefundSubmitter executor, EscalationTools escalation, FlowObserver observer) {
        this.factsFactory = new RefundReviewContextFactory(mcp, () -> "");
        this.catalog = new PolicyCatalogConsumer(mcp);
        this.reviewer = reviewer;
        this.executor = executor;
        this.escalation = escalation;
        this.observer = Objects.requireNonNullElse(observer, FlowObserver.NOOP);
    }

    public String apply(String sessionId, RefundRequest request) {
        if (sessionId == null || sessionId.isBlank() || request == null
                || request.orderId() == null || request.orderId() <= 0) {
            return "退款申请无法确认，请联系人工客服。";
        }
        Long orderId = request.orderId();
        Key key = new Key(sessionId, orderId);
        State previous = states.putIfAbsent(key, State.IN_REVIEW);
        if (previous == State.REJECTED) {
            return "订单 " + orderId + " 在本次会话中已记录人工升级请求。请联系人工客服继续处理。";
        }
        if (previous == State.IN_REVIEW) {
            return "订单 " + orderId + " 正在复核，本次重复请求未提交。";
        }
        try {
            observe("FACTS", orderId, "STARTED");
            CheckedRefundFacts facts;
            try {
                facts = factsFactory.requireEligible(orderId, request.reason(), request.originalUserRequest());
            } catch (RefundReviewContextFactory.RefundNotEligibleException e) {
                observe("FACTS", orderId, "REJECTED");
                return "订单 " + orderId + " 当前不可退：" + e.getMessage() + "。本次未提交退款。";
            } catch (RuntimeException e) {
                failure("FACTS", orderId, e, "MISSING_EVIDENCE");
                return rejectAndEscalate(key, orderId, "退款资格事实无法核实", null, null, false);
            }
            FlowObserver.event(observer, "FACTS", orderId, Map.of("status", "COMPLETED",
                    "policyCode", facts.policyCode(), "policyFingerprint", facts.catalogFingerprint()));

            PolicyEvidence evidence;
            observe("POLICY", orderId, "STARTED");
            try {
                CatalogSnapshot snapshot = catalog.refresh();
                evidence = catalog.requireMatching(snapshot, facts.catalogFingerprint(), facts.policyCode());
            } catch (RuntimeException e) {
                failure("POLICY", orderId, e, "MISSING_EVIDENCE");
                return rejectAndEscalate(key, orderId, "政策目录无法核实", null, null, false);
            }
            if (POLICY_ROLE_INSTRUCTION.matcher(evidence.title()).find()
                    || POLICY_ROLE_INSTRUCTION.matcher(evidence.clauseText()).find()) {
                observe("POLICY", orderId, "REJECTED");
                return rejectAndEscalate(key, orderId, "政策条款含有指令式内容", null, null, false);
            }
            FlowObserver.event(observer, "POLICY", orderId, Map.of("status", "COMPLETED",
                    "policyCode", evidence.code(), "policyFingerprint", evidence.fingerprint(),
                    "sourceKey", evidence.code(), "sourceDigest", FlowObserver.textDigest(evidence.clauseText())));
            FlowObserver.source(observer, evidence.code(), evidence.clauseText(), FlowObserver.textDigest(evidence.clauseText()));

            RefundReviewContext context = new RefundReviewContext(facts.originalUserRequest(),
                    facts.trustedOrder(), facts.trustedEligibility(), facts.candidateAction(), evidence);
            ToolTrace.record("review", ToolTrace.Status.CALLED);
            observe("REVIEW", orderId, "STARTED");
            ReviewVerdict verdict;
            try {
                verdict = reviewer.apply(context);
            } catch (RuntimeException e) {
                failure("REVIEW", orderId, e, "MODEL_ERROR");
                ToolTrace.record("review", ToolTrace.Status.TRANSPORT_ERROR);
                return rejectAndEscalate(key, orderId, "复核未完成", null, null, true);
            }
            if (verdict == null || !verdict.validFor(evidence.code())) {
                FlowObserver.event(observer, "REVIEW", orderId, Map.of("role", "REVIEW", "status", "FAILED",
                        "reviewOutcome", "INVALID", "errorCategory", "REVIEW_FORMAT_ERROR"));
                ToolTrace.record("review", ToolTrace.Status.ERROR);
                return rejectAndEscalate(key, orderId, "复核结论无法核实", null, null, true);
            }
            if (!verdict.approved()) {
                FlowObserver.event(observer, "REVIEW", orderId, Map.of("role", "REVIEW", "status", "REJECTED",
                        "reviewOutcome", "REJECTED", "policyCode", verdict.citedPolicyCode()));
                ToolTrace.record("review", ToolTrace.Status.ERROR);
                return rejectAndEscalate(key, orderId, "退款复核未通过", verdict, context, true);
            }
            ToolTrace.record("review", ToolTrace.Status.OK);
            FlowObserver.event(observer, "REVIEW", orderId, Map.of("role", "REVIEW", "status", "COMPLETED",
                    "reviewOutcome", "APPROVED", "policyCode", verdict.citedPolicyCode()));

            try {
                FlowObserver.event(observer, "EXECUTION", orderId, Map.of("status", "STARTED",
                        "policyCode", evidence.code(), "policyFingerprint", evidence.fingerprint()));
                String receipt = executor.apply(orderId, request.reason(), evidence.fingerprint(), evidence.code());
                observe("EXECUTION", orderId, "COMPLETED");
                return receipt;
            } catch (RefundExecutor.StaleReviewException e) {
                FlowObserver.event(observer, "EXECUTION", orderId, Map.of("status", "REJECTED", "businessCode", 50005,
                        "exceptionClass", e.getClass().getName()));
                return "订单 " + orderId + " 的复核依据已过期，本次未产生新退款记录；请联系人工客服核实。";
            } catch (RuntimeException e) {
                failure("EXECUTION", orderId, e, "UNRESOLVED_WRITE");
                return "订单 " + orderId + " 的退款申请提交结果无法确认。请联系人工客服核实退款状态。";
            }
        } finally {
            states.remove(key, State.IN_REVIEW);
        }
    }

    private String rejectAndEscalate(Key key, Long orderId, String summary,
                                     ReviewVerdict verdict, RefundReviewContext context,
                                     boolean terminal) {
        if (terminal) states.replace(key, State.IN_REVIEW, State.REJECTED);
        escalation.escalateToHuman(orderId, summary);
        observe("ESCALATION", orderId, "COMPLETED");
        String verified = verifiedReason(verdict, context);
        return "订单 " + orderId + " 的退款申请未执行。" + verified
                + "已记录，请联系人工客服核实。";
    }

    private void observe(String phase, Long orderId, String status) {
        FlowObserver.event(observer, phase, orderId, Map.of("status", status,
                "role", phase.equals("REVIEW") ? "REVIEW" : "ORCHESTRATOR"));
    }

    private void failure(String phase, Long orderId, RuntimeException failure, String category) {
        FlowObserver.event(observer, phase, orderId, Map.of("status", "FAILED", "exceptionClass", failure.getClass().getName(),
                "errorCategory", category));
    }

    private static String verifiedReason(ReviewVerdict verdict, RefundReviewContext context) {
        if (verdict == null || context == null) return GENERIC_REASON;
        for (ReviewFault fault : verdict.faults()) {
            String evidence = fault.evidence();
            boolean grounded = switch (fault.category()) {
                // CheckedRefundFacts already reconciles the order and eligibility fields we can prove.
                // A model's quoted token cannot establish a fact or policy contradiction.
                // Those claims need structured predicates we do not have in this phase.
                case "FACT_CONFLICT", "POLICY_CONFLICT" -> false;
                case "USER_INSTRUCTION_RISK" -> explicitUserRisk(evidence)
                        && context.originalUserRequest().contains(evidence);
                default -> false;
            };
            if (!grounded) return GENERIC_REASON;
        }
        ReviewFault first = verdict.faults().get(0);
        return switch (first.category()) {
            case "USER_INSTRUCTION_RISK" -> "复核发现原始诉求需要人工核查。";
            default -> GENERIC_REASON;
        };
    }

    private static boolean explicitUserRisk(String evidence) {
        return evidence.contains("跳过查证") || evidence.contains("别查")
                || evidence.contains("不要核实") || evidence.contains("无需核实")
                || evidence.contains("我是管理员") || evidence.contains("我是老板");
    }
}
