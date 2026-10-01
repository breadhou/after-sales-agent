package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.output.TokenUsage;

/** Shared trial-wide logical request and reported-token accounting. */
public final class TrialBudget {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOGICAL_REQUESTS = 12;

    private final int requestLimit;
    private final long reportedTokenAllowance;
    private int logicalModelRequests;
    private int recordedUsageRequests;
    private int unknownUsageRequests;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private long reportedTokens;

    public TrialBudget(int requestAllowance, long reportedTokenAllowance) {
        if (requestAllowance < 0 || reportedTokenAllowance < 0) {
            throw new IllegalArgumentException("Trial allowances must be non-negative");
        }
        this.requestLimit = Math.min(MAX_LOGICAL_REQUESTS, requestAllowance);
        this.reportedTokenAllowance = reportedTokenAllowance;
    }

    /** Reserves one logical provider call before delegation, or stops without reserving it. */
    public synchronized void beforeRequest() {
        if (logicalModelRequests >= requestLimit || reportedTokens >= reportedTokenAllowance) {
            throw new BudgetExceededException();
        }
        logicalModelRequests++;
    }

    /** Records one response's known usage; null means the request returned no usage. */
    public synchronized void record(TokenUsage usage) {
        if (recordedUsageRequests >= logicalModelRequests) {
            throw new IllegalStateException("Token usage has no preceding logical model request");
        }
        if (usage == null) {
            recordedUsageRequests++;
            unknownUsageRequests++;
            return;
        }

        // Read all SDK values before mutating the ledger, so a broken accessor remains an
        // outstanding (therefore unknown) request in snapshot().
        Long prompt = nonNegative(usage.inputTokenCount());
        Long completion = nonNegative(usage.outputTokenCount());
        Long total = nonNegative(usage.totalTokenCount());
        recordedUsageRequests++;
        promptTokens = addKnown(promptTokens, prompt);
        completionTokens = addKnown(completionTokens, completion);
        totalTokens = addKnown(totalTokens, total);
        if (prompt == null || completion == null || total == null) unknownUsageRequests++;

        // Prefer the SDK's reported total. If it is absent, count only the known prompt/completion
        // portion so a partial response remains useful without turning unknown values into zero.
        reportedTokens += total != null ? total : knownSubtotal(prompt, completion);
    }

    /** Returns the exact WorkerResult.metering projection, including outstanding calls as unknown. */
    public synchronized JsonNode snapshot() {
        int unknown = unknownUsageRequests + logicalModelRequests - recordedUsageRequests;
        ObjectNode result = JSON.createObjectNode().put("logicalModelRequests", logicalModelRequests);
        putNullable(result, "promptTokens", promptTokens);
        putNullable(result, "completionTokens", completionTokens);
        putNullable(result, "totalTokens", totalTokens);
        result.put("usageComplete", unknown == 0);
        result.put("unknownUsageRequests", unknown);
        return result;
    }

    private static Long nonNegative(Integer count) {
        return count != null && count >= 0 ? count.longValue() : null;
    }

    private static Long addKnown(Long aggregate, Long value) {
        if (value == null) return aggregate;
        return aggregate == null ? value : aggregate + value;
    }

    private static long knownSubtotal(Long prompt, Long completion) {
        return (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
    }

    private static void putNullable(ObjectNode result, String name, Long value) {
        if (value == null) result.putNull(name);
        else result.put(name, value);
    }

    /** Fixed-category signal for the worker when the next logical request cannot be sent. */
    public static final class BudgetExceededException extends RuntimeException {
        private BudgetExceededException() { super("BUDGET_STOP"); }
        public String errorCategory() { return "BUDGET_STOP"; }
    }
}
