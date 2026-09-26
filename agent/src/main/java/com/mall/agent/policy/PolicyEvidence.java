package com.mall.agent.policy;

/** A policy clause retrieved from the catalog generation used for one review. */
public record PolicyEvidence(String fingerprint, String code, String title, String clauseText) {
}
