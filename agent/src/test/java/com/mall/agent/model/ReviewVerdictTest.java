package com.mall.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewVerdictTest {

    @Test
    void approvedVerdictMustNotShadowTheRecordAccessor() {
        ReviewVerdict verdict = ReviewVerdict.approvedVerdict();

        assertTrue(verdict.approved());
        assertTrue(verdict.faults().isEmpty());
    }
}
