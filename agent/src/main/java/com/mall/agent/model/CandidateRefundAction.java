package com.mall.agent.model;

/** 候选敏感动作；它是复核对象，不是决策 Agent 的推理过程。 */
public record CandidateRefundAction(Long orderId, String reason) { }
