package com.mall.agent.model;

import java.util.List;
import java.util.Set;

/** Structured reviewer result. Only an exact, internally consistent approval can authorize a write. */
public record ReviewVerdict(boolean approved, String citedPolicyCode, List<ReviewFault> faults) {
    private static final Set<String> CATEGORIES = Set.of(
            "FACT_CONFLICT", "POLICY_CONFLICT", "USER_INSTRUCTION_RISK", "UNCERTAIN");

    public ReviewVerdict {
        if (faults != null) faults = List.copyOf(faults);
    }

    public boolean validFor(String expectedCode) {
        if (expectedCode == null || expectedCode.isBlank() || !expectedCode.equals(citedPolicyCode)
                || faults == null) {
            return false;
        }
        if (approved) {
            return faults.isEmpty();
        }
        return !faults.isEmpty() && faults.stream().allMatch(fault ->
                fault != null && CATEGORIES.contains(fault.category())
                        && fault.evidence() != null && !fault.evidence().isBlank()
                        && expectedCode.equals(fault.policyCode()));
    }

    public boolean authorizes(String expectedCode) {
        return approved && validFor(expectedCode);
    }
}
