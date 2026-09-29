package com.mall.agent.flow;

import com.mall.agent.agent.DecisionAgent;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.PendingRefund;
import com.mall.agent.model.RefundRequest;
import com.mall.agent.tools.EscalationTools;
import com.mall.agent.tools.ExplanationRequestTools;
import com.mall.agent.tools.RefundHandoffTools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在模型调用外处理订单选择、用户确认和可信回复的会话入口。 */
public final class ConversationCoordinator {

    private static final Pattern LABELED_ORDER_NUMBER = Pattern.compile("订单(?:号|ID|id)?\\s*[:：#]?\\s*([0-9]+)");
    private static final Pattern LINKED_ORDER_NUMBER = Pattern.compile("(?:或者|或|和|及|以及|与|跟|、|,|，|/)\\s*([0-9]+)");
    private static final Pattern SELECT_COMMAND = Pattern.compile("/select-refund-order ([0-9]+)");
    private static final Pattern CONFIRM_COMMAND = Pattern.compile("/confirm-refund ([0-9]+)");
    private static final String ESCALATION_REPLY = "已记录，请联系人工客服";
    private static final Pattern REFUND_DIRECTIVE = Pattern.compile(
            "(?:请|麻烦)?(?:帮我|给我)\\s*(?:申请)?退(?:款|货)?|(?<!申)请退(?:款|货)?"
                    + "|我要\\s*(?:申请)?退(?:款|货)?|退给我|退这单|退掉");
    private static final Pattern REASON_CUE = Pattern.compile(
            "(?:退款理由|理由|原因)\\s*(?:[:：]|是)\\s*|因为\\s*|由于\\s*");
    private static final Pattern REASON_END = Pattern.compile("[，,。；;！？!?\\r\\n]");
    private static final Pattern REFUND_CLAUSE_BOUNDARY = Pattern.compile("[，,。；;！？!?\\r\\n]+");
    private static final Pattern MONEY_RETURN_REQUEST = Pattern.compile("(?:钱|款)\\s*(?:打回|退回)(?:来)?");
    private static final Pattern REFUND_OUTCOME_TOPIC = Pattern.compile(
            "退款|退货|退回|打回|退到|退给|到账");
    private static final Pattern OUTCOME_ASSERTION = Pattern.compile(
            "已|已经|成功|完成|办好|办妥|处理好|到账|退回|提交");
    private static final Set<String> NON_SUBSTANTIVE_REASONS = Set.of(
            "退款", "退货", "申请退款", "流程", "规则", "政策", "步骤", "原因",
            "谢谢", "辛苦了", "麻烦了");

    private final DecisionAgent decision;
    private final RefundHandoffTools handoffTools;
    private final EscalationTools escalationTools;
    private final Supplier<List<Long>> orderLister;
    private final Function<Long, String> eligibilityReply;
    private final Predicate<Long> historicallyRefunded;
    private final BiFunction<String, RefundRequest, String> workflow;
    private final ExplanationRequestTools explanationTools;
    private final BiFunction<String, Long, String> explanation;
    private final Map<String, PendingRefund> pendingBySession = new ConcurrentHashMap<>();

    public ConversationCoordinator(DecisionAgent decision, RefundHandoffTools handoffTools,
                                   EscalationTools escalationTools, Supplier<List<Long>> orderLister,
                                   Function<Long, String> eligibilityReply,
                                   Predicate<Long> historicallyRefunded,
                                   BiFunction<String, RefundRequest, String> workflow) {
        this(decision, handoffTools, escalationTools, orderLister, eligibilityReply,
                historicallyRefunded, workflow, new ExplanationRequestTools(),
                (input, orderId) -> "当前无可靠依据回答该资料问题，请联系人工客服核实。");
    }

    public ConversationCoordinator(DecisionAgent decision, RefundHandoffTools handoffTools,
                                   EscalationTools escalationTools, Supplier<List<Long>> orderLister,
                                   Function<Long, String> eligibilityReply,
                                   Predicate<Long> historicallyRefunded,
                                   BiFunction<String, RefundRequest, String> workflow,
                                   ExplanationRequestTools explanationTools,
                                   BiFunction<String, Long, String> explanation) {
        this.decision = Objects.requireNonNull(decision);
        this.handoffTools = Objects.requireNonNull(handoffTools);
        this.escalationTools = Objects.requireNonNull(escalationTools);
        this.orderLister = Objects.requireNonNull(orderLister);
        this.eligibilityReply = Objects.requireNonNull(eligibilityReply);
        this.historicallyRefunded = Objects.requireNonNull(historicallyRefunded);
        this.workflow = Objects.requireNonNull(workflow);
        this.explanationTools = Objects.requireNonNull(explanationTools);
        this.explanation = Objects.requireNonNull(explanation);
    }

    /** 同一决策工具实例一次只处理一轮，标记和升级记录不能跨会话串用。 */
    public synchronized String handleTurn(String sessionId, String rawInput) {
        if (sessionId == null || sessionId.isBlank() || rawInput == null) {
            throw new IllegalArgumentException("会话或输入缺失");
        }
        String input = rawInput.trim();
        if (input.isEmpty()) {
            return "";
        }
        if (input.startsWith("/confirm-refund")) {
            return confirm(sessionId, input);
        }
        if (input.startsWith("/select-refund-order")) {
            return select(sessionId, input);
        }
        if (input.equals("/cancel-refund") || input.equals("取消退款")) {
            pendingBySession.remove(sessionId);
            return "本次待确认退款申请已取消，未提交退款。";
        }

        // 一旦用户开始新话题或改变诉求，旧的待确认理由和订单不再有效。
        pendingBySession.remove(sessionId);
        handoffTools.clear();
        explanationTools.clear();
        int priorEscalations = escalationTools.recordCount();
        final String modelReply;
        try {
            modelReply = decision.handle(sessionId, rawInput);
        } catch (RuntimeException e) {
            handoffTools.clear();
            explanationTools.clear();
            return escalationTools.recordCount() > priorEscalations
                    ? ESCALATION_REPLY : "本轮请求暂时无法确认，请联系人工客服。";
        }
        RefundHandoffTools.Signals signals = handoffTools.takeSignals();
        Long explanationOrderMarker = explanationTools.takeOrderId();
        if (escalationTools.recordCount() > priorEscalations) {
            return ESCALATION_REPLY;
        }
        if (looksLikeRefundApplication(rawInput)) {
            if (signals.handoffs().isEmpty()) {
                return "本轮未提交退款。请明确提供订单 ID 与理由后重新申请。";
            }
            return respondToHandoff(sessionId, rawInput, signals.handoffs());
        }
        if (looksLikeEligibilityQuestion(input)) {
            return respondToEligibility(rawInput);
        }
        // A mistaken eligibility marker on a logistics turn must not replace its answer.
        // It also cannot coexist with a generated explanation on that same turn.
        if (!signals.eligibilityQuestions().isEmpty()) explanationOrderMarker = null;
        if (isNegatedRefundRequest(input)) {
            return "本次未提交退款。";
        }
        if (!signals.handoffs().isEmpty()) {
            return "本次未提交退款。若需要申请退款，请明确告知订单 ID 和理由。";
        }
        if (explanationOrderMarker != null) {
            if (refundStatusQuestion(input)) return verifiedHistoricalStatusReply(rawInput);
            ParsedOrderIds ids = explicitOrderIds(rawInput);
            if (ids.invalid() || ids.values().size() > 1 || explanationOrderMarker < 0
                    || (explanationOrderMarker > 0 && (ids.values().size() != 1
                    || !explanationOrderMarker.equals(ids.values().get(0))))) {
                return "当前无可靠依据回答该资料问题，请联系人工客服核实。";
            }
            Long orderId = ids.values().isEmpty() ? null : ids.values().get(0);
            try {
                String reply = explanation.apply(rawInput, orderId);
                return reply == null || reply.isBlank()
                        ? "当前无可靠依据回答该资料问题，请联系人工客服核实。" : reply;
            } catch (RuntimeException e) {
                return "当前无可靠依据回答该资料问题，请联系人工客服核实。";
            }
        }
        if (modelReply == null) {
            return "暂时无法回答，请联系人工客服。";
        }
        if (refundOutcomeClaim(modelReply)
                || (refundTopic(input) && !isGeneralReturnQuestion(input))) {
            return verifiedHistoricalStatusReply(rawInput);
        }
        return modelReply;
    }

    private String verifiedHistoricalStatusReply(String rawInput) {
        ParsedOrderIds ids = explicitOrderIds(rawInput);
        if (!ids.invalid() && ids.values().size() == 1) {
            Long orderId = ids.values().get(0);
            try {
                if (historicallyRefunded.test(orderId)) {
                    return "订单 " + orderId + " 当前状态为 REFUNDED；本次未提交退款。";
                }
            } catch (RuntimeException ignored) {
                // 读失败不是退款已完成的证据。
            }
            return "订单 " + orderId + " 的退款状态无法确认；本次未提交退款，请联系人工客服核实。";
        }
        return "退款状态无法确认；本次未提交退款，请联系人工客服核实。";
    }

    private static boolean refundTopic(String text) {
        return text.contains("退款") || text.contains("退货") || text.contains("退回")
                || text.contains("款项") || text.contains("到账")
                || (text.contains("钱") && (text.contains("退") || text.contains("还")))
                || MONEY_RETURN_REQUEST.matcher(text).find();
    }

    private static boolean refundStatusQuestion(String text) {
        return text.contains("退款状态") || text.contains("退款进度")
                || text.contains("已退款") || text.contains("退款成功")
                || text.contains("到账");
    }

    private static boolean refundOutcomeClaim(String text) {
        if (text.contains("REFUNDED")) {
            return true;
        }
        for (String clause : REFUND_CLAUSE_BOUNDARY.split(text)) {
            if (REFUND_OUTCOME_TOPIC.matcher(clause).find()
                    && OUTCOME_ASSERTION.matcher(clause).find()) {
                return true;
            }
        }
        return false;
    }

    private String respondToHandoff(String sessionId, String rawInput,
                                    List<CandidateRefundAction> handoffs) {
        if (!looksLikeRefundApplication(rawInput) || handoffs.size() != 1) {
            return "本次未提交退款。若需要申请退款，请明确告知订单 ID 和理由。";
        }
        String reason = groundedReason(rawInput, handoffs.get(0).reason());
        if (reason == null) {
            return "请明确提供不超过 512 字的退款理由；本次未提交退款。";
        }

        ParsedOrderIds ids = explicitOrderIds(rawInput);
        if (ids.invalid()) {
            return "订单 ID 无效；本次未提交退款。";
        }
        if (ids.values().size() == 1) {
            Long orderId = ids.values().get(0);
            pendingBySession.put(sessionId, PendingRefund.confirming(sessionId, orderId, reason, rawInput));
            return confirmationReply(orderId, reason);
        }

        List<Long> listed = trustedOrderList();
        if (listed.isEmpty()) {
            return "请提供唯一的订单 ID 和退款理由；本次未提交退款。";
        }
        pendingBySession.put(sessionId, PendingRefund.selecting(sessionId, reason, rawInput, listed));
        StringBuilder reply = new StringBuilder("请先从本会话列出的订单中选择退款目标：");
        for (Long orderId : listed) {
            reply.append("\n订单 ").append(orderId).append("：/select-refund-order ").append(orderId);
        }
        return reply.append("\n候选理由：").append(reason)
                .append("。选择后仍需确认；本次未提交退款。").toString();
    }

    private String respondToEligibility(String rawInput) {
        ParsedOrderIds ids = explicitOrderIds(rawInput);
        if (ids.invalid()) {
            return "订单 ID 无效，无法查询退款资格；本次未提交退款。";
        }
        if (ids.values().size() != 1) {
            return "请提供唯一的订单 ID，以便查询退款资格；本次未提交退款。";
        }
        Long orderId = ids.values().get(0);
        try {
            String reply = eligibilityReply.apply(orderId);
            return reply == null || reply.isBlank()
                    ? "订单 " + orderId + " 的退款资格无法确认；本次未提交退款。" : reply;
        } catch (RuntimeException e) {
            return "订单 " + orderId + " 的退款资格无法确认；本次未提交退款。";
        }
    }

    private String select(String sessionId, String input) {
        PendingRefund pending = pendingBySession.get(sessionId);
        if (pending == null || pending.stage() != PendingRefund.Stage.SELECT_ORDER) {
            return "当前没有待选择的退款订单；本次未提交退款。";
        }
        Matcher command = SELECT_COMMAND.matcher(input);
        if (!command.matches()) {
            return "请选择本会话列出的订单，格式：/select-refund-order <订单 ID>。";
        }
        Long orderId = validLong(command.group(1));
        if (orderId == null || !pending.selectableOrders().contains(orderId)) {
            return "该订单不在本会话列出的清单中；本次未提交退款。";
        }
        pendingBySession.put(sessionId, PendingRefund.confirming(sessionId, orderId,
                pending.reason(), pending.originalUserRequest()));
        return confirmationReply(orderId, pending.reason());
    }

    private String confirm(String sessionId, String input) {
        PendingRefund pending = pendingBySession.get(sessionId);
        if (pending == null || pending.stage() != PendingRefund.Stage.CONFIRM
                || !sessionId.equals(pending.sessionId())) {
            return "当前没有待确认的退款申请；本次未提交退款。";
        }
        // 无论成功与否，确认动作只可消费一次。错误订单或附加理由须重新提请。
        pendingBySession.remove(sessionId);
        Matcher command = CONFIRM_COMMAND.matcher(input);
        if (!command.matches()) {
            return "确认格式无效，待确认申请已取消；请重新提出退款申请。";
        }
        Long orderId = validLong(command.group(1));
        if (orderId == null || !orderId.equals(pending.orderId())) {
            return "确认的订单 ID 与候选不一致，待确认申请已取消；请重新提出退款申请。";
        }
        RefundRequest request = new RefundRequest(orderId, pending.reason(), pending.originalUserRequest());
        try {
            String reply = workflow.apply(sessionId, request);
            return reply == null || reply.isBlank()
                    ? "订单 " + orderId + " 的退款申请结果无法确认，请联系人工客服核实。" : reply;
        } catch (RuntimeException e) {
            return "订单 " + orderId + " 的退款申请结果无法确认，请联系人工客服核实。";
        }
    }

    private List<Long> trustedOrderList() {
        try {
            List<Long> fetched = orderLister.get();
            if (fetched == null) {
                return List.of();
            }
            Set<Long> unique = new LinkedHashSet<>();
            for (Long id : fetched) {
                if (id != null && id > 0) {
                    unique.add(id);
                }
            }
            return List.copyOf(unique);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String confirmationReply(Long orderId, String reason) {
        return "请确认退款申请：订单 " + orderId + "；理由：" + reason
                + "。确认请单独输入 /confirm-refund " + orderId + "。本次未提交退款。";
    }

    private static String groundedReason(String rawInput, String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 512
                || candidate.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        String reason = candidate.trim();
        Matcher cue = REASON_CUE.matcher(rawInput);
        int cueEnd = -1;
        while (cue.find()) {
            cueEnd = cue.end();
        }
        if (cueEnd < 0) {
            return null;
        }
        String reasonSpan = rawInput.substring(cueEnd);
        Matcher end = REASON_END.matcher(reasonSpan);
        if (end.find()) {
            reasonSpan = reasonSpan.substring(0, end.start());
        }
        if (substantiveReason(reason) && reasonSpan.contains(reason)) {
            return reason;
        }
        String firstClause = reason.split("[，,。；;！？!?]", 2)[0].trim();
        if (substantiveReason(firstClause) && reasonSpan.contains(firstClause)) {
            return firstClause;
        }
        return null;
    }

    private static boolean substantiveReason(String reason) {
        return reason.length() >= 2 && !reason.matches("[0-9\\s:：#，,。；;!?！？]+")
                && !reason.matches("(?:花了|金额|价格)?\\s*[¥￥]?\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:元|块|块钱)?")
                && !reason.matches("订单(?:号|ID|id)?\\s*[:：#]?\\s*[0-9]+")
                && !reason.matches(".*(?:怎么|如何|什么|是否|能否|可否|[?？]).*")
                && !refundOutcomeClaim(reason)
                && !NON_SUBSTANTIVE_REASONS.contains(reason);
    }

    private static boolean looksLikeRefundApplication(String rawInput) {
        String text = rawInput.trim();
        if (isNegatedRefundRequest(text)) {
            return false;
        }
        boolean directRequest = REFUND_DIRECTIVE.matcher(text).find();
        directRequest |= text.startsWith("退款") && LABELED_ORDER_NUMBER.matcher(text).find();
        directRequest |= (text.startsWith("申请退款") || text.startsWith("我想申请退款")
                || text.startsWith("我想退款"))
                && (LABELED_ORDER_NUMBER.matcher(text).find()
                || text.matches("^(?:我想)?申请退款\\s+[0-9].*"));
        if (isGeneralReturnQuestion(text) && !directRequest) {
            return false;
        }
        return directRequest || text.contains("申请退") || text.contains("我要退")
                || text.contains("要退款") || text.contains("我想退")
                || text.contains("帮我退") || text.contains("给我退")
                || text.contains("退给我") || text.contains("请退")
                || text.contains("退这单") || text.contains("退掉")
                || text.matches("^退(?:款|货)?\\s*[0-9].*")
                || text.matches("^退款[　 ].*");
    }

    private static boolean isNegatedRefundRequest(String text) {
        for (String negation : List.of("不要退", "别退", "不用退", "取消退", "不想退", "无需退")) {
            if (text.contains(negation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGeneralReturnQuestion(String text) {
        if (text.contains("如何申请退款") || text.contains("怎么申请退款")) {
            return true;
        }
        return (text.contains("退货") || text.contains("退款"))
                && (text.contains("流程") || text.contains("规则") || text.contains("政策")
                || text.contains("步骤"))
                && (text.contains("什么") || text.contains("如何") || text.contains("怎么"));
    }

    private static boolean looksLikeEligibilityQuestion(String text) {
        return text.contains("能退") || text.contains("可以退") || text.contains("可退吗")
                || text.contains("退款资格") || text.contains("是否可退")
                || text.contains("能退款吗");
    }

    private static ParsedOrderIds explicitOrderIds(String rawInput) {
        Matcher labeled = LABELED_ORDER_NUMBER.matcher(rawInput);
        Set<Long> values = new LinkedHashSet<>();
        while (labeled.find()) {
            Long id = validLong(labeled.group(1));
            if (id == null) {
                return new ParsedOrderIds(List.of(), true);
            }
            values.add(id);
        }
        if (!values.isEmpty()) {
            Matcher linked = LINKED_ORDER_NUMBER.matcher(rawInput);
            while (linked.find()) {
                Long id = validLong(linked.group(1));
                if (id == null) {
                    return new ParsedOrderIds(List.of(), true);
                }
                values.add(id);
            }
        }
        return new ParsedOrderIds(new ArrayList<>(values), false);
    }

    private static Long validLong(String digits) {
        try {
            long id = Long.parseLong(digits);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record ParsedOrderIds(List<Long> values, boolean invalid) { }
}
