package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.AgentRuntime;
import com.mall.agent.config.AgentConfig;
import com.mall.agent.flow.FlowObserver;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.RefundReviewContext;
import com.mall.agent.model.ReviewVerdict;
import com.mall.agent.policy.PolicyEvidence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One isolated trial; no judge labels enter the shared production runtime. */
public final class TrialExecutor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z][a-z0-9-]{0,63})}}");
    private final Function<String, McpClient> clients;
    private final ChatModel model;

    /** The factory receives a logical actor alias. This worker owns every returned client. */
    public TrialExecutor(Function<String, McpClient> clients, ChatModel model) {
        this.clients = Objects.requireNonNull(clients);
        this.model = model;
    }

    public JsonNode execute(CaseSpec spec, JsonNode bindings, Path workDir, int requestAllowance, long reportedTokenAllowance) {
        return execute(spec, bindings, prepareOutputDirectory(workDir), requestAllowance, reportedTokenAllowance);
    }

    JsonNode execute(CaseSpec spec, JsonNode bindings, OutputDirectory output, int requestAllowance, long reportedTokenAllowance) {
        JsonNode document = Objects.requireNonNull(spec).document();
        validateBindings(document, bindings);
        Path directory = output.consume();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        BindingIndex index = BindingIndex.fromPrivateJson(bindings);
        SafeEventRecorder recorder = new SafeEventRecorder(bindings.path("runId").textValue(), document.path("caseId").textValue(),
                bindings.path("trialId").textValue(), index, store);
        TrialBudget budget = new TrialBudget(requestAllowance, reportedTokenAllowance);
        long deadline = System.nanoTime() + Duration.ofSeconds(300).toNanos();
        WriteBoundary writes = new WriteBoundary();
        List<McpClient> owned = new ArrayList<>();
        JsonNode control = document.path("control");
        String mode = document.path("mode").asText();
        try {
            Files.writeString(directory.resolve("case-input.json"), document.toString(), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("binding-input.json"), bindings.toString(), StandardCharsets.UTF_8);
            if (mode.equals("CONTROLLED") && control.path("target").asText().equals("BACKEND_TRANSACTION")) {
                try (AutoCloseable ignored = recorder.beginTurn("worker", 0)) { error(recorder, "FIXTURE_ERROR"); }
            } else {
                Map<String, ChatModel> roles = new HashMap<>();
                for (String role : List.of("DIALOGUE", "REVIEW", "EXPLANATION"))
                    roles.put(role, roleModel(role, mode, control, bindings, budget, recorder, deadline));
                if (mode.equals("REVIEW_ONLY")) {
                    try (AutoCloseable ignored = recorder.beginTurn("review", 0)) {
                        RefundReviewContext context = reviewContext(document.path("reviewInput"));
                        FlowObserver.source(recorder, context.policyEvidence().code(), context.policyEvidence().clauseText(),
                                FlowObserver.textDigest(context.policyEvidence().clauseText()));
                        ReviewVerdict verdict = AgentConfig.reviewSafely(AgentConfig.reviewAgent(roles.get("REVIEW")), context, recorder);
                        // A null or invalid verdict is unavailable evidence, never a semantic rejection.
                        if (verdict == null && recorder.errorCategory() == null) error(recorder, "MODEL_ERROR");
                        if (verdict != null && !verdict.validFor(context.policyEvidence().code())) error(recorder, "REVIEW_FORMAT_ERROR");
                        store.writeFinalReply("review", 0, verdict == null ? "REVIEW_UNAVAILABLE" : JSON.writeValueAsString(verdict));
                    }
                } else if (mode.equals("CONTROLLED") && control.path("target").asText().equals("MCP_CONTRACT")) {
                    McpClient client = client(bindings.path("activeActor").textValue(), owned, writes, index, recorder);
                    int step = 0;
                    for (JsonNode call : control.path("toolCalls")) {
                        try (AutoCloseable ignored = recorder.beginTurn("mcp-contract", step)) {
                            checkDeadline(deadline);
                            ObjectNode arguments = (ObjectNode)call.path("arguments").deepCopy();
                            for (String field : List.of("orderId", "productId")) if (arguments.has(field)) {
                                String alias = arguments.path(field).asText();
                                Matcher match = PLACEHOLDER.matcher(alias);
                                if (!match.matches()) throw new IllegalArgumentException("Invalid tool target");
                                arguments.put(field, Long.parseLong(bindings.path(field.equals("orderId") ? "orders" : "products")
                                        .path(match.group(1)).path(field).textValue()));
                            }
                            ToolExecutionRequest request = ToolExecutionRequest.builder().name(call.path("toolName").textValue())
                                    .arguments(arguments.toString()).build();
                            try {
                                var reply = client.executeTool(request);
                                store.writeFinalReply("mcp-contract", step, reply == null || reply.resultText() == null ? "MCP_UNAVAILABLE" : reply.resultText());
                                if (reply == null) error(recorder, "MISSING_EVIDENCE");
                            } catch (dev.langchain4j.exception.ToolExecutionException failure) {
                                String actual = ObservedMcpClient.matchedBusinessError(request, failure, index, recorder, store);
                                if (actual == null) throw failure;
                                store.writeFinalReply("mcp-contract", step, actual);
                            }
                            if (writes.unknown()) break;
                        }
                        step++;
                    }
                } else {
                    if (mode.equals("CONTROLLED") && (!Set.of("REAL", "SUBSTITUTED").contains(control.path("components").path("MCP").asText())
                            || !control.path("components").path("BACKEND").asText().equals("REAL"))) throw new IllegalArgumentException("Unsupported chain components");
                    Map<String, AgentRuntime> sessions = new LinkedHashMap<>();
                    Map<String, String> sessionActors = new HashMap<>();
                    int turnIndex = 0;
                    for (JsonNode turn : document.path("turns")) {
                        checkDeadline(deadline);
                        String session = turn.path("sessionAlias").textValue(), actor = turn.path("actorAlias").textValue();
                        try (AutoCloseable ignored = recorder.beginTurn(session, turnIndex)) {
                            String oldActor = sessionActors.putIfAbsent(session, actor);
                            if (oldActor != null && !oldActor.equals(actor)) throw new IllegalArgumentException("Session actor changed");
                            AgentRuntime runtime = sessions.get(session);
                            if (runtime == null) {
                                McpClient client = client(actor, owned, writes, index, recorder);
                                if (mode.equals("CONTROLLED")) client = ControlledAdapters.mcp(client, control, recorder);
                                runtime = AgentRuntime.create(roles.get("DIALOGUE"), roles.get("REVIEW"), roles.get("EXPLANATION"),
                                        client, allowedProducts(bindings), session, recorder, ignoredEscalation -> { });
                                sessions.put(session, runtime);
                            }
                            String reply = runtime.coordinator().handleTurn(session, render(turn.path("input").textValue(), document, bindings));
                            store.writeFinalReply(session, turnIndex, reply);
                        }
                        turnIndex++;
                        // Production may fail closed and return a template after a model/observation failure.
                        // Stop here rather than initiating another turn that could submit again.
                        if (recorder.errorCategory() != null || writes.unknown()) break;
                    }
                }
            }
        } catch (Throwable failure) {
            try (AutoCloseable ignored = recorder.beginTurn("worker", 0)) {
                error(recorder, failure instanceof TrialBudget.BudgetExceededException ? "BUDGET_STOP"
                        : writes.unknown() ? "UNRESOLVED_WRITE" : failure instanceof IOException || failure instanceof java.io.UncheckedIOException
                        ? "MISSING_EVIDENCE" : "FIXTURE_ERROR");
            } catch (Exception ignored) { recorder.onObservationFailure(failure); }
        }

        // All business calls/turns have stopped. Drain observation work within the worker deadline.
        boolean drained = false;
        try {
            drained = recorder.awaitObservations(Duration.ofNanos(Math.max(0, Math.min(Duration.ofSeconds(30).toNanos(), deadline-System.nanoTime()))));
        } catch (Throwable failure) { recorder.onObservationFailure(failure); }
        finally {
            for (McpClient client : owned) try { client.close(); } catch (Throwable failure) { recorder.onObservationFailure(failure); }
        }
        if (!drained || !recorder.evidenceComplete()) {
            if (recorder.errorCategory() == null) recorder.onObservationFailure(new IllegalStateException());
        }
        try {
            StringBuilder events = new StringBuilder();
            recorder.snapshot().forEach(event -> events.append(event).append('\n'));
            Files.writeString(directory.resolve("events.jsonl"), events.toString(), StandardCharsets.UTF_8);
            store.writeEvidenceFile();
        } catch (Throwable failure) { recorder.onObservationFailure(failure); }
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("runId", bindings.path("runId").textValue())
                .put("caseId", document.path("caseId").textValue()).put("trialId", bindings.path("trialId").textValue())
                .put("eventsFile", "events.jsonl").put("privateEvidenceFile", store.evidenceFile());
        result.set("metering", budget.snapshot());
        result.put("terminalEvidence", writes.terminal());
        if (recorder.errorCategory() == null) result.putNull("errorCategory"); else result.put("errorCategory", recorder.errorCategory());
        return result;
    }

    private ChatModel roleModel(String role, String mode, JsonNode control, JsonNode bindings, TrialBudget budget, SafeEventRecorder recorder, long deadline) {
        ChatModel unavailable = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                FlowObserver.event(recorder, ControlledAdapters.phase(role), null, Map.of("role", role, "status", "FAILED", "errorCategory", "MISSING_EVIDENCE"));
                throw new IllegalStateException("MISSING_EVIDENCE");
            }
        };
        ChatModel metered = new MeteredChatModel(model == null ? unavailable : model, role, budget, recorder);
        ChatModel guarded = new ControlledAdapters.ForwardingModel(metered) {
            private ChatResponse call(Supplier<ChatResponse> call) {
                checkDeadline(deadline);
                try { return call.get(); }
                catch (TrialBudget.BudgetExceededException failure) {
                    // Observe before reviewSafely/coordinator conservatively consumes the exception.
                    FlowObserver.event(recorder, ControlledAdapters.phase(role), null,
                            Map.of("role", role, "status", "FAILED", "errorCategory", failure.errorCategory()));
                    throw failure;
                }
            }
            @Override public ChatResponse chat(ChatRequest request) { return call(() -> delegate.chat(request)); }
            @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) { return call(() -> delegate.chat(request, options)); }
        };
        if (!mode.equals("CONTROLLED")) return guarded;
        String state = control.path("components").path(role).asText();
        if (state.equals("REAL")) return guarded;
        if (state.equals("SUBSTITUTED") && role.equals(control.path("script").path("role").asText()))
            return ControlledAdapters.model(guarded, control, role, recorder, bindings);
        return unavailable;
    }

    private McpClient client(String actor, List<McpClient> owned, WriteBoundary writes, BindingIndex index, SafeEventRecorder recorder) {
        McpClient actual = Objects.requireNonNull(clients.apply(actor));
        owned.add(actual);
        return ObservedMcpClient.wrap(writes.wrap(actual), index, recorder);
    }
    private static RefundReviewContext reviewContext(JsonNode input) {
        JsonNode candidate = input.path("candidateAction"), policy = input.path("policyEvidence");
        return new RefundReviewContext(input.path("originalUserRequest").textValue(), input.path("trustedOrder").textValue(),
                input.path("trustedEligibility").textValue(), new CandidateRefundAction(Long.parseLong(candidate.path("orderId").textValue()), candidate.path("reason").textValue()),
                new PolicyEvidence(policy.path("fingerprint").textValue(), policy.path("code").textValue(), policy.path("title").textValue(), policy.path("clauseText").textValue()));
    }
    private static Set<Long> allowedProducts(JsonNode bindings) {
        Set<Long> allowed = new HashSet<>(); bindings.path("products").forEach(row -> allowed.add(Long.parseLong(row.path("productId").textValue()))); return allowed;
    }
    private static String render(String input, JsonNode document, JsonNode bindings) {
        Matcher matches = PLACEHOLDER.matcher(input); StringBuilder result = new StringBuilder();
        while (matches.find()) {
            String alias = matches.group(1), replacement;
            if (bindings.path("orders").has(alias)) replacement = bindings.path("orders").path(alias).path("orderId").textValue();
            else {
                JsonNode product = document.path("fixture").path("products").path(alias);
                replacement = product.path("name").textValue();
                if (product.path("source").asText().equals("DEMO_READONLY")) replacement = demoName(product.path("logicalKey").textValue());
                if (replacement == null) throw new IllegalArgumentException("Product presentation unavailable");
            }
            matches.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matches.appendTail(result); return result.toString();
    }
    private static String demoName(String logicalKey) {
        Path root = Path.of("").toAbsolutePath();
        if (root.getFileName().toString().equals("agent")) root = root.getParent();
        try {
            for (JsonNode product : JSON.readTree(Files.readString(root.resolve("data/demo-products.json"), StandardCharsets.UTF_8)))
                if (product.path("logicalKey").asText().equals(logicalKey)) return product.path("name").textValue();
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        throw new IllegalArgumentException("Product presentation unavailable");
    }
    private static void checkDeadline(long deadline) { if (System.nanoTime() >= deadline) throw new IllegalStateException("WORKER_DEADLINE"); }
    private static void error(SafeEventRecorder recorder, String category) { FlowObserver.event(recorder, "SESSION", null, Map.of("status", "FAILED", "errorCategory", category)); }
    private static Path confinedDirectory(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize(); Files.createDirectories(normalized);
            if (!normalized.toRealPath().equals(normalized)) throw new IllegalArgumentException("Evidence directory escapes scope");
            return normalized;
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    /** Claims a fresh output scope before opening any sensitive sink or business component. */
    static OutputDirectory prepareOutputDirectory(Path path) {
        Path directory = confinedDirectory(path);
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Worker output directory is already occupied");
            // CREATE_NEW establishes one owner; the marker stays even after a failed attempt.
            Files.writeString(directory.resolve("worker-output-owner"), "schemaVersion=1\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return new OutputDirectory(directory);
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot claim worker output directory", failure); }
    }

    static final class OutputDirectory {
        private final Path path;
        private final AtomicBoolean used = new AtomicBoolean();
        private OutputDirectory(Path path) { this.path = path; }
        Path path() { return path; }
        private Path consume() {
            if (!used.compareAndSet(false, true)) throw new IllegalArgumentException("Worker output ownership already consumed");
            return path;
        }
    }

    private static void validateBindings(JsonNode document, JsonNode b) {
        if (b == null || !b.isObject() || !fields(b).equals(Set.of("schemaVersion", "runId", "caseId", "trialId", "activeActor", "actors", "orders", "products"))
                || !b.path("schemaVersion").isIntegralNumber() || b.path("schemaVersion").intValue()!=1
                || !b.path("caseId").equals(document.path("caseId")) || !b.path("actors").isObject()) throw new IllegalArgumentException("Invalid worker bindings");
        BindingIndex.fromPrivateJson(b);
        for (String id : List.of("runId", "trialId")) if (!b.path(id).isTextual() || !b.path(id).textValue().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid worker identity");
        JsonNode fixture = document.path("fixture");
        b.path("orders").forEach(row -> {
            if (!row.isObject() || !fields(row).equals(Set.of("orderId", "orderNo")) || !row.path("orderNo").isTextual() || row.path("orderNo").textValue().isBlank())
                throw new IllegalArgumentException("Invalid order binding");
        });
        b.path("products").forEach(row -> {
            if (!row.isObject() || !fields(row).equals(Set.of("productId", "skus")) || !row.path("skus").isObject()) throw new IllegalArgumentException("Invalid product binding");
            row.path("skus").fields().forEachRemaining(sku -> {
                if (!sku.getKey().matches("[a-z][a-z0-9-]{0,63}") || !sku.getValue().isTextual() || !sku.getValue().textValue().matches("[1-9][0-9]*"))
                    throw new IllegalArgumentException("Invalid SKU binding");
            });
        });
        b.path("actors").fields().forEachRemaining(actor -> {
            JsonNode row = actor.getValue();
            if (!actor.getKey().matches("[a-z][a-z0-9-]{0,63}") || !row.isObject() || !fields(row).contains("userId")
                    || !Set.of("userId", "userToken").containsAll(fields(row)) || !row.path("userId").isTextual()
                    || !row.path("userId").textValue().matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid actor binding");
            if (row.has("userToken") && (!actor.getKey().equals(b.path("activeActor").asText()) || !row.path("userToken").isTextual() || row.path("userToken").textValue().isBlank()))
                throw new IllegalArgumentException("Invalid actor token scope");
        });
        boolean synthetic = document.path("mode").asText().equals("REVIEW_ONLY") && fixture.isEmpty();
        if (synthetic) { if (!b.path("activeActor").isNull() || !b.path("actors").isEmpty()) throw new IllegalArgumentException("Synthetic actor bindings"); }
        else {
            if (!b.path("activeActor").equals(fixture.path("activeActor")) || !fields(b.path("orders")).equals(fields(fixture.path("orders")))
                    || !fields(b.path("products")).equals(fields(fixture.path("products")))) throw new IllegalArgumentException("Fixture binding mismatch");
            for (JsonNode alias : fixture.path("actors")) if (!b.path("actors").has(alias.textValue())) throw new IllegalArgumentException("Missing actor");
        }
    }
    private static Set<String> fields(JsonNode node) { Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields; }

    /** Real send/return boundary below substitutions; it never claims a business refund occurred. */
    private static final class WriteBoundary {
        private volatile String terminal = "NOT_SENT";
        boolean unknown() { return terminal.equals("UNKNOWN"); }
        String terminal() { return terminal; }
        McpClient wrap(McpClient delegate) {
            return (McpClient)Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class}, (proxy, method, arguments) -> {
                boolean submit = Set.of("executeTool", "executeToolAsync").contains(method.getName())
                        && ((ToolExecutionRequest)arguments[0]).name().equals("submit_refund");
                if (!submit) return ControlledAdapters.invoke(delegate, method, arguments);
                if (unknown()) throw new IllegalStateException("UNRESOLVED_WRITE");
                terminal = "UNKNOWN";
                Object response = ControlledAdapters.invoke(delegate, method, arguments);
                if (response instanceof CompletableFuture<?> future) future.whenComplete((reply, failure) -> { if (failure == null && reply != null) terminal = "COMPLETED"; });
                else if (response != null) terminal = "COMPLETED";
                return response;
            });
        }
    }
}
