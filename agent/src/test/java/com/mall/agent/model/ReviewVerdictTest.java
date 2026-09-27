package com.mall.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewVerdictTest {

    @Test
    void structuredVerdictRequiresExactCodeAndConsistentFaults() {
        assertTrue(new ReviewVerdict(true, "SHIPPED_NOT_RECEIVED", java.util.List.of())
                .authorizes("SHIPPED_NOT_RECEIVED"));
        assertFalse(new ReviewVerdict(true, "OTHER", java.util.List.of())
                .authorizes("SHIPPED_NOT_RECEIVED"));
        assertFalse(new ReviewVerdict(true, "", java.util.List.of())
                .authorizes("SHIPPED_NOT_RECEIVED"));
        assertFalse(new ReviewVerdict(true, "SHIPPED_NOT_RECEIVED", java.util.List.of(
                new ReviewFault("UNCERTAIN", "疑点", "SHIPPED_NOT_RECEIVED")))
                .authorizes("SHIPPED_NOT_RECEIVED"));
        assertFalse(new ReviewVerdict(false, "SHIPPED_NOT_RECEIVED", java.util.List.of())
                .authorizes("SHIPPED_NOT_RECEIVED"));
    }

    @Test
    void missingFaultsFieldNeverAuthorizes() {
        assertFalse(new ReviewVerdict(true, "SHIPPED_NOT_RECEIVED", null)
                .authorizes("SHIPPED_NOT_RECEIVED"));
    }

    @Test
    void approvedVerdictMustNotShadowTheRecordAccessor() {
        ReviewVerdict verdict = new ReviewVerdict(true, "SHIPPED_NOT_RECEIVED", java.util.List.of());

        assertTrue(verdict.approved());
        assertTrue(verdict.faults().isEmpty());
    }
}
