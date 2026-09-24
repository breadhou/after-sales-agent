package com.mall.agent.model;

import java.time.LocalDateTime;

/** 升级人工的记录。本阶段只落日志与内存，不建工单表——那是客服系统的职责。 */
public record EscalationRecord(String sessionId, Long orderId, String reason, LocalDateTime at) {

    public static EscalationRecord of(String sessionId, Long orderId, String reason) {
        return new EscalationRecord(sessionId, orderId, reason, LocalDateTime.now());
    }
}
