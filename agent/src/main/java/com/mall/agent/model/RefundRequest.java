package com.mall.agent.model;

/** 用户已确认的退款申请；原话在后续可信编排与复核中保留。 */
public record RefundRequest(Long orderId, String reason, String originalUserRequest) {

    public RefundRequest {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("订单 ID 无效");
        }
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw new IllegalArgumentException("退款理由无效");
        }
        if (originalUserRequest == null || originalUserRequest.isBlank()) {
            throw new IllegalArgumentException("缺少用户原话");
        }
    }
}
