package com.mall.agent.mcp.tools;

import com.mall.agent.mcp.SupermallClient;

/**
 * Provides the policy catalog used to ground after-sales decisions and explanations.
 *
 * <p>{@link SupermallClient} unwraps the Result envelope, so this method returns
 * the catalog data object containing its fingerprint and clauses.</p>
 */
public class PolicyTools {

    private final SupermallClient client;

    public PolicyTools(SupermallClient client) {
        this.client = client;
    }

    /** Returns the complete current policy catalog data object. */
    public String listPolicyClauses() {
        return client.get("/api/after-sales/policies").toString();
    }
}
