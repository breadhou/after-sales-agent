package com.mall.agent.model;

import com.mall.agent.policy.PolicyEvidence;

/** 由可信编排层构造、交给复核 Agent 的最小上下文。 */
public record RefundReviewContext(
        String originalUserRequest,
        String trustedOrder,
        String trustedEligibility,
        CandidateRefundAction candidateAction,
        PolicyEvidence policyEvidence) { }
