package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.flow.FlowObserver;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Strict public projection with a sticky failure flag, independent of business decisions. */
public final class SafeEventRecorder implements FlowObserver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PHASES = Set.of("MODEL", "SESSION", "CONFIRMATION", "FACTS", "POLICY", "REVIEW", "EXECUTION", "ESCALATION", "EXPLANATION", "MCP", "BACKEND");
    private static final Set<String> ROLES = Set.of("DIALOGUE", "REVIEW", "EXPLANATION", "ORCHESTRATOR", "MCP", "BACKEND");
    private static final Set<String> STATUSES = Set.of("STARTED", "COMPLETED", "CALLED", "RESPONSE_RECEIVED", "REJECTED", "BUSINESS_ERROR", "TRANSPORT_ERROR", "FAILED", "SKIPPED", "SCRIPTED");
    private static final Set<String> TOOLS = Set.of("get_order", "list_user_orders", "get_logistics", "get_refund_eligibility", "list_policy_clauses", "list_on_shelf_products", "get_product_detail", "submit_refund", "review", "handoff_refund", "ask_refund_eligibility", "escalate_to_human", "request_explanation", "UNKNOWN_TOOL");
    private static final Set<String> ATTRIBUTES = Set.of("role", "status", "tool", "callId", "targetKind", "businessCode", "policyCode", "policyFingerprint", "sourceKey", "sourceDigest", "durationMs", "promptTokens", "completionTokens", "totalTokens", "modelName", "exceptionClass", "httpStatus", "replyKind", "errorCategory", "reviewOutcome");
    private static final Set<String> ERRORS = Set.of("FIXTURE_ERROR", "UNBOUND_TARGET", "MISSING_EVIDENCE", "MODEL_ERROR", "REVIEW_FORMAT_ERROR", "BUDGET_STOP", "UNRESOLVED_WRITE", "FIXTURE_CREATION_UNKNOWN");
    record Turn(String sessionAlias, int turnIndex) { }
    private final String runId, caseId, trialId;
    private final BindingIndex index;
    private final PrivateEvidenceStore store;
    private final ThreadLocal<Turn> turn = new ThreadLocal<>();
    private final List<JsonNode> events = new ArrayList<>();
    private final Set<CompletableFuture<Void>> pendingObservations = new HashSet<>();
    private long sequence;
    private String errorCategory;
    private boolean missing;

    public SafeEventRecorder(String runId, String caseId, String trialId, BindingIndex index, PrivateEvidenceStore store) {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || caseId == null || !caseId.matches("[A-Z][A-Z0-9_-]{0,63}")
                || trialId == null || !trialId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Invalid event identity");
        this.runId = runId; this.caseId = caseId; this.trialId = trialId;
        this.index = Objects.requireNonNull(index); this.store = Objects.requireNonNull(store);
    }

    public AutoCloseable beginTurn(String sessionAlias, int turnIndex) {
        if (sessionAlias == null || !sessionAlias.matches("[a-z][a-z0-9-]{0,63}") || turnIndex < 0)
            throw new IllegalArgumentException("Invalid turn identity");
        Turn previous = turn.get();
        turn.set(new Turn(sessionAlias, turnIndex));
        return () -> { if (previous == null) turn.remove(); else turn.set(previous); };
    }

    Turn captureTurn() { return turn.get(); }

    synchronized CompletableFuture<Void> observationStarted() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        pendingObservations.add(completion);
        return completion;
    }

    void observationFinished(CompletableFuture<Void> completion) {
        synchronized (this) { pendingObservations.remove(completion); }
        completion.complete(null);
    }

    /** After stopping new calls, drain observations before exporting public and private snapshots. */
    public boolean awaitObservations(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative observation timeout");
        long budget = timeout.toNanos(), started = System.nanoTime();
        while (true) {
            CompletableFuture<?>[] pending;
            synchronized (this) {
                if (pendingObservations.isEmpty()) return !missing;
                pending = pendingObservations.toArray(CompletableFuture<?>[]::new);
            }
            // Never hold the recorder monitor while callbacks need it to emit their evidence.
            try {
                CompletableFuture.allOf(pending).get(Math.max(0, budget - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                onObservationFailure(interrupted);
                return false;
            } catch (ExecutionException | TimeoutException failure) {
                onObservationFailure(failure);
                return false;
            }
        }
    }

    @Override public void onEvent(String phase, Long targetId, Map<String, Object> attributes) {
        record(captureTurn(), phase, targetId, attributes);
    }

    synchronized void record(Turn origin, String phase, Long targetId, Map<String, Object> attributes) {
        try {
            if (origin == null || !PHASES.contains(phase) || attributes == null || !ATTRIBUTES.containsAll(attributes.keySet()))
                throw new IllegalArgumentException("Invalid observation projection");
            String role = value(attributes, "role", "ORCHESTRATOR"), status = value(attributes, "status", "COMPLETED");
            if (!ROLES.contains(role) || !STATUSES.contains(status)) throw new IllegalArgumentException("Invalid event enum");
            String targetKind = value(attributes, "targetKind", "ORDER");
            if (!Set.of("ORDER", "PRODUCT").contains(targetKind)) throw new IllegalArgumentException("Invalid target kind");
            String target = targetKind.equals("PRODUCT") ? index.productAlias(targetId) : index.orderAlias(targetId);
            if (target.equals("UNBOUND")) mark("UNBOUND_TARGET");
            ObjectNode event = JSON.createObjectNode().put("runId", runId).put("caseId", caseId).put("trialId", trialId)
                    .put("sessionAlias", origin.sessionAlias()).put("turnIndex", origin.turnIndex()).put("sequence", ++sequence)
                    .put("callId", value(attributes, "callId", "flow-" + sequence)).put("target", target).put("phase", phase)
                    .put("role", role).put("status", status);
            text(event, attributes, "tool", TOOLS, null, 64);
            text(event, attributes, "policyCode", null, "[A-Z][A-Z0-9_-]{0,63}", 64);
            text(event, attributes, "policyFingerprint", null, null, Integer.MAX_VALUE);
            text(event, attributes, "sourceKey", null, null, Integer.MAX_VALUE);
            if (!event.path("sourceKey").isNull()) {
                String key = index.sourceKey(event.path("sourceKey").asText());
                if (key == null) throw new IllegalArgumentException("Unknown source key");
                event.put("sourceKey", key);
            }
            text(event, attributes, "sourceDigest", null, "[a-f0-9]{64}", 64);
            text(event, attributes, "modelName", null, null, 256);
            text(event, attributes, "exceptionClass", null, "[A-Za-z_$][A-Za-z0-9_$.]*", 256);
            number(event, attributes, "businessCode", 0L, null);
            number(event, attributes, "httpStatus", 100L, 599L);
            number(event, attributes, "durationMs", 0L, null);
            if (event.path("durationMs").isNull()) event.put("durationMs", 0);
            for (String field : List.of("promptTokens", "completionTokens", "totalTokens")) number(event, attributes, field, 0L, null);
            events.add(event);
            if (attributes.containsKey("errorCategory")) {
                String category = value(attributes, "errorCategory", "");
                if (!ERRORS.contains(category)) throw new IllegalArgumentException("Invalid error category");
                mark(category);
                append(privateRecord(origin, event.path("callId").asText(), "ERROR").put("errorCategory", category));
            }
            if (attributes.containsKey("replyKind")) {
                String kind = value(attributes, "replyKind", "");
                if (!Set.of("TRUSTED_TEMPLATE", "SOURCE_ORIGINAL", "FREE_TEXT").contains(kind)) throw new IllegalArgumentException("Invalid reply kind");
                append(privateRecord(origin, event.path("callId").asText(), "REPLY").put("replyKind", kind));
            }
            if (attributes.containsKey("reviewOutcome")) {
                String outcome = value(attributes, "reviewOutcome", "");
                if (!Set.of("APPROVED", "REJECTED", "INVALID", "ERROR").contains(outcome)) throw new IllegalArgumentException("Invalid review outcome");
                append(privateRecord(origin, event.path("callId").asText(), "REVIEW_OUTCOME").put("outcome", outcome));
            }
        } catch (Throwable failure) { onObservationFailure(failure); }
    }

    @Override public synchronized void onSourceEvidence(String sourceKey, String text, String digest) {
        try {
            Turn origin = captureTurn();
            String logical = index.sourceKey(sourceKey);
            if (origin == null || logical == null || text == null || digest == null || !digest.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid source evidence");
            append(privateRecord(origin, "source-" + (sequence + 1), "SOURCE")
                    .put("sourceKey", logical).put("sourceDigest", digest).put("text", text));
            onEvent("EXPLANATION", null, Map.of("role", "EXPLANATION", "sourceKey", sourceKey, "sourceDigest", digest));
        } catch (Throwable failure) { onObservationFailure(failure); }
    }

    synchronized void returnedTargets(Turn origin, String callId, List<String> aliases) {
        try {
            if (origin == null) throw new IllegalArgumentException("Missing originating turn");
            ObjectNode record = privateRecord(origin, callId, "RETURNED_TARGETS");
            record.set("aliases", JSON.valueToTree(aliases));
            append(record);
        } catch (Throwable failure) { onObservationFailure(failure); }
    }

    private ObjectNode privateRecord(Turn origin, String callId, String kind) {
        return JSON.createObjectNode().put("sessionAlias", origin.sessionAlias()).put("turnIndex", origin.turnIndex())
                .put("callId", callId).put("kind", kind);
    }
    private void append(JsonNode record) { store.append(record); }
    private void mark(String category) { missing = true; if (errorCategory == null) errorCategory = category; }
    @Override public synchronized void onObservationFailure(Throwable failure) { mark("MISSING_EVIDENCE"); }
    public synchronized boolean evidenceComplete() { return !missing && pendingObservations.isEmpty(); }
    public synchronized String errorCategory() { return errorCategory; }
    public synchronized List<JsonNode> snapshot() { return events.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }

    private static String value(Map<String, Object> attributes, String key, String fallback) {
        Object value = attributes.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text) || text.isBlank() || text.codePoints().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Invalid event text");
        return text;
    }
    private static void text(ObjectNode event, Map<String, Object> attributes, String key, Set<String> allowed, String pattern, int max) {
        if (attributes.get(key) == null) { event.putNull(key); return; }
        String text = value(attributes, key, "");
        if (text.length() > max || (allowed != null && !allowed.contains(text)) || (pattern != null && !text.matches(pattern)))
            throw new IllegalArgumentException("Invalid event text projection");
        event.put(key, text);
    }
    private static void number(ObjectNode event, Map<String, Object> attributes, String key, Long min, Long max) {
        Object value = attributes.get(key);
        if (value == null) { event.putNull(key); return; }
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Invalid event number");
        long number = ((Number) value).longValue();
        if ((min != null && number < min) || (max != null && number > max)) throw new IllegalArgumentException("Invalid event number range");
        event.put(key, number);
    }
}
