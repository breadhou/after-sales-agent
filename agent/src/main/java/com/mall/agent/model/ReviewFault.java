package com.mall.agent.model;

/** A reviewer finding. Its evidence is untrusted until checked against the supplied context. */
public record ReviewFault(String category, String evidence, String policyCode) { }
