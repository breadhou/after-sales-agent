package com.mall.agent.trace;

import java.util.Set;

/** Emits only fixed tool names and statuses for Task 6 audit. */
public final class ToolTrace {

    private static final Set<String> NAMES = Set.of(
            "get_order", "list_user_orders", "get_logistics", "get_refund_eligibility",
            "list_policy_clauses", "submit_refund", "request_refund", "review",
            "escalate_to_human");

    public enum Status {
        CALLED("called"), OK("ok"), ERROR("error"), TRANSPORT_ERROR("transport_error");

        private final String text;

        Status(String text) {
            this.text = text;
        }
    }

    private ToolTrace() {
    }

    public static void record(String tool, Status status) {
        String safeTool = tool != null && NAMES.contains(tool) ? tool : "unknown";
        System.err.println("TASK6_TRACE tool=" + safeTool + " status=" + status.text);
    }
}
