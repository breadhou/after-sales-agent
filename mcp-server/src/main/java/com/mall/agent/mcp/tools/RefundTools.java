package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.mcp.SupermallClient;

/**
 * Provides after-sales eligibility and refund submission tools.
 *
 * <p>The sole write method has no amount argument. Supermall calculates the
 * refund amount from the order, so this tool surface cannot request an
 * arbitrary amount.</p>
 */
public class RefundTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SupermallClient client;

    public RefundTools(SupermallClient client) {
        this.client = client;
    }

    /** Gets the order's refund eligibility, amount, applicable policy, and existing-refund flag. */
    public String getRefundEligibility(Long orderId) {
        JsonNode data = client.get("/api/orders/" + orderId + "/refund-eligibility");
        ObjectNode picked = MAPPER.createObjectNode();
        for (String field : new String[]{"eligible", "reason", "policyCode", "policyTitle",
                "refundableAmount", "refundExists"}) {
            if (data.has(field)) {
                picked.set(field, data.get(field));
            }
        }
        picked.put("orderId", orderId);
        return picked.toString();
    }

    /** Submits a refund request; Supermall determines the amount from the order. */
    public String submitRefund(Long orderId, String reason) {
        String body = MAPPER.createObjectNode().put("reason", reason).toString();
        return client.post("/api/orders/" + orderId + "/refund/execute", body).toString();
    }
}
