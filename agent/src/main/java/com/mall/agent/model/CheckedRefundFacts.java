package com.mall.agent.model;

import java.math.BigDecimal;

/** 已由可信代码核对、可供政策复核使用的订单和退款资格事实。 */
public record CheckedRefundFacts(
        String originalUserRequest,
        String trustedOrder,
        String trustedEligibility,
        CandidateRefundAction candidateAction,
        String orderStatus,
        String policyCode,
        String catalogFingerprint,
        BigDecimal refundableAmount) { }
