package com.mall.agent.flow;

import com.mall.agent.agent.DecisionAgent;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.PendingRefund;
import com.mall.agent.model.RefundRequest;
import com.mall.agent.tools.EscalationTools;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在模型调用外处理订单选择、用户确认和可信回复的会话入口。 */
public final class ConversationCoordinator {

    private static final Pattern LABELED_ORDER_NUMBER = Pattern.compile("订单(?:号|ID|id)?\\s*[:：#]?\\s*([0-9]+)");
    private static final Pattern UNLABELED_ORDER_NUMBER = Pattern.compile(
            "(?:申请退款|我要退款|我想退|帮我退款|给我退款|请退款|请退|退款|退)\\s*[:：#]?\\s*([0-9]+)");
    private static final Pattern LINKED_ORDER_NUMBER = Pattern.compile("(?:或者|或|和|及|以及|与|跟|、|,|，|/)\\s*([0-9]+)");
    private static final Pattern SELECT_COMMAND = Pattern.compile("/select-refund-order ([0-9]+)");
    private static final Pattern CONFIRM_COMMAND = Pattern.compile("/confirm-refund ([0-9]+)");
    private static final String ESCALATION_REPLY = "已记录，请联系人工客服";
    private static final String CODE_OWNED_GENERIC_REASON = "不想要了";

    private final DecisionAgent decision;
    private final RefundHandoffTools handoffTools;
    private final EscalationTools escalationTools;
    private final Supplier<List<Long>> orderLister;
    private final Function<Long, String> eligibilityReply;
    private final BiFunction<String, RefundRequest, String> workflow;
    private final Map<String, PendingRefund> pendingBySession = new ConcurrentHashMap<>();

    public ConversationCoordinator(DecisionAgent decision, RefundHandoffTools handoffTools,
                                   EscalationTools escalationTools, Supplier<List<Long>> orderLister,
                                   Function<Long, String> eligibilityReply,
                                   BiFunction<String, RefundRequest, String> workflow) {
        this.decision = Objects.requireNonNull(decision);
        this.handoffTools = Objects.requireNonNull(handoffTools);
        this.escalationTools = Objects.requireNonNull(escalationTools);
        this.orderLister = Objects.requireNonNull(orderLister);
        this.eligibilityReply = Objects.requireNonNull(eligibilityReply);
        this.workflow = Objects.requireNonNull(workflow);
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
        int priorEscalations = escalationTools.recordCount();
        final String modelReply;
        try {
            modelReply = decision.handle(sessionId, rawInput);
        } catch (RuntimeException e) {
            handoffTools.clear();
            return escalationTools.recordCount() > priorEscalations
                    ? ESCALATION_REPLY : "本轮请求暂时无法确认，请联系人工客服。";
        }
        RefundHandoffTools.Signals signals = handoffTools.takeSignals();
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
        if (isNegatedRefundRequest(input)) {
            return "本次未提交退款。";
        }
        if (!signals.handoffs().isEmpty()) {
            return "本次未提交退款。若需要申请退款，请明确告知订单 ID 和理由。";
        }
        return modelReply == null ? "暂时无法回答，请联系人工客服。" : modelReply;
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
        if (rawInput.contains(reason)) {
            return reason;
        }
        String firstClause = reason.split("[，,。；;！？!?]", 2)[0].trim();
        if (firstClause.length() >= 2 && rawInput.contains(firstClause)) {
            return firstClause;
        }
        // 没有原话依据时只接受代码定义的中性标签，不能显示任意模型文本。
        return CODE_OWNED_GENERIC_REASON.equals(reason) ? reason : null;
    }

    private static boolean looksLikeRefundApplication(String rawInput) {
        String text = rawInput.trim();
        if (isNegatedRefundRequest(text) || isGeneralReturnQuestion(text)) {
            return false;
        }
        return text.contains("申请退") || text.contains("我要退")
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
        boolean hasLabel = labeled.find();
        Matcher matcher = hasLabel ? labeled.reset() : UNLABELED_ORDER_NUMBER.matcher(rawInput);
        Set<Long> values = new LinkedHashSet<>();
        while (matcher.find()) {
            if (!hasLabel && clearlyNotOrderId(rawInput, matcher.start(1), matcher.end(1))) {
                continue;
            }
            Long id = validLong(matcher.group(1));
            if (id == null) {
                return new ParsedOrderIds(List.of(), true);
            }
            values.add(id);
        }
        if (hasLabel) {
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

    private static boolean clearlyNotOrderId(String input, int start, int end) {
        String before = input.substring(Math.max(0, start - 3), start);
        String after = input.substring(end).stripLeading();
        return before.endsWith("金额") || before.endsWith("价格")
                || before.endsWith("尺码") || before.endsWith("￥") || before.endsWith("¥")
                || after.startsWith("元") || after.startsWith("块")
                || after.startsWith("件") || after.startsWith("天") || after.startsWith("码")
                || after.startsWith("年") || after.startsWith("月") || after.startsWith("日")
                || after.startsWith("-");
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
