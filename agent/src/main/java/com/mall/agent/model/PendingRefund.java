package com.mall.agent.model;

import java.util.List;

/** 当前会话内尚未授权执行的退款请求。待选单与待确认是不同状态。 */
public record PendingRefund(String sessionId, Stage stage, Long orderId, String reason,
                            String originalUserRequest, List<Long> selectableOrders) {

    public enum Stage { SELECT_ORDER, CONFIRM }

    public PendingRefund {
        selectableOrders = List.copyOf(selectableOrders);
        if (sessionId == null || sessionId.isBlank() || stage == null
                || reason == null || reason.isBlank() || reason.length() > 512
                || originalUserRequest == null || originalUserRequest.isBlank()) {
            throw new IllegalArgumentException("待处理退款申请无效");
        }
        if (stage == Stage.SELECT_ORDER && (orderId != null || selectableOrders.isEmpty())) {
            throw new IllegalArgumentException("待选单状态无效");
        }
        if (stage == Stage.CONFIRM && (orderId == null || orderId <= 0
                || !selectableOrders.isEmpty())) {
            throw new IllegalArgumentException("待确认状态无效");
        }
    }

    public static PendingRefund selecting(String sessionId, String reason, String originalUserRequest,
                                          List<Long> selectableOrders) {
        return new PendingRefund(sessionId, Stage.SELECT_ORDER, null, reason,
                originalUserRequest, selectableOrders);
    }

    public static PendingRefund confirming(String sessionId, Long orderId, String reason,
                                           String originalUserRequest) {
        return new PendingRefund(sessionId, Stage.CONFIRM, orderId, reason,
                originalUserRequest, List.of());
    }
}
