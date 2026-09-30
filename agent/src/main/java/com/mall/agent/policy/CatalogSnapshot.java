package com.mall.agent.policy;

import java.util.List;
import java.util.Objects;

/** An immutable policy catalog generation returned by one MCP response. */
public record CatalogSnapshot(String fingerprint, List<PolicyEvidence> clauses) {

    public CatalogSnapshot {
        Objects.requireNonNull(fingerprint, "fingerprint");
        clauses = List.copyOf(clauses);
    }
}
