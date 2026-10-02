package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.config.AgentConfig;
import com.mall.agent.flow.ConversationCoordinator;
import com.mall.agent.flow.FlowObserver;
import com.mall.agent.flow.RefundWorkflow;
import com.mall.agent.knowledge.ExplanationService;
import com.mall.agent.knowledge.ExplanationDraft;
import com.mall.agent.model.*;
import com.mall.agent.policy.PolicyEvidence;
import com.mall.agent.tools.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long A = 9007199254741001L;
    private static final long B = 9007199254741003L;
    private static final long PRODUCT = 9007199254741011L;
    private static final String SECRET = "secret-sentinel-token-and-private-message";
    @TempDir Path directory;

    private BindingIndex bindings() throws Exception {
        return BindingIndex.fromPrivateJson(JSON.readTree("""
                {"schemaVersion":1,"runId":"run-1","caseId":"NORMAL-001","trialId":"trial-1",
                 "activeActor":null,"actors":{},"orders":{
                 "order-a":{"orderId":"9007199254741001","orderNo":"private-a"},
                 "order-b":{"orderId":"9007199254741003","orderNo":"private-b"}},
                 "products":{"product-a":{"productId":"9007199254741011","skus":{}}}}
                """));
    }

    private SafeEventRecorder recorder(BindingIndex index, PrivateEvidenceStore store) {
        return new SafeEventRecorder("run-1", "NORMAL-001", "trial-1", index, store);
    }

    @Test
    void actualSubmitHeadersAreCapturedWithoutChangingRequestOrOutcome() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        ToolExecutionRequest original = request("submit_refund", "{\"orderId\":" + A
                + ",\"reason\":\"" + SECRET + "\",\"expectedPolicyCode\":\"ACTUAL_CODE\","
                + "\"expectedCatalogFingerprint\":\"actual-fp\",\"extra\":\"" + SECRET + "\"}");
        ToolExecutionResult result = ok("private receipt " + SECRET);
        McpClient observed = ObservedMcpClient.wrap(fake(passed -> {
            assertSame(original, passed);
            assertEquals(A, assertDoesNotThrow(() -> JSON.readTree(passed.arguments())).path("orderId").longValue());
            return result;
        }), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 2)) {
            assertSame(result, observed.executeTool(original));
        }
        assertTrue(events.awaitObservations(Duration.ofSeconds(1)));
        assertEquals(2, events.snapshot().size());
        String callId = events.snapshot().get(0).path("callId").asText();
        for (JsonNode event : events.snapshot()) {
            assertEquals("ACTUAL_CODE", event.path("policyCode").asText());
            assertEquals("actual-fp", event.path("policyFingerprint").asText());
            assertEquals(callId, event.path("callId").asText());
            assertEquals("order-a", event.path("target").asText());
            assertEquals(2, event.path("turnIndex").asInt());
        }
        assertNoPrivateData(events.snapshot());
        assertFalse(store.snapshot().toString().contains(SECRET));
    }

    @Test
    void actualSubmitAsyncHeadersKeepFutureAndOriginOnFailure() throws Exception {
        BindingIndex index = bindings();
        SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(directory));
        CompletableFuture<ToolExecutionResult> original = new CompletableFuture<>();
        ToolExecutionRequest request = request("submit_refund", "{\"orderId\":" + B
                + ",\"reason\":\"" + SECRET + "\",\"expectedPolicyCode\":\"C2\",\"expectedCatalogFingerprint\":\"fp-2\"}");
        McpClient delegate = (McpClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, arguments) -> { assertSame(request, arguments[0]); return original; });
        McpClient observed = ObservedMcpClient.wrap(delegate, index, events);
        try (AutoCloseable turn = events.beginTurn("session-b", 3)) {
            assertSame(original, observed.executeToolAsync(request, null));
        }
        RuntimeException failure = new RuntimeException(SECRET);
        try (AutoCloseable turn = events.beginTurn("session-a", 4)) { original.completeExceptionally(failure); }
        assertSame(failure, assertThrows(ExecutionException.class, () -> original.get()).getCause());
        assertTrue(events.awaitObservations(Duration.ofSeconds(1)));
        for (JsonNode event : events.snapshot()) {
            assertEquals("C2", event.path("policyCode").asText());
            assertEquals("fp-2", event.path("policyFingerprint").asText());
            assertEquals("session-b", event.path("sessionAlias").asText());
            assertEquals("order-b", event.path("target").asText());
            assertEquals(3, event.path("turnIndex").asInt());
        }
        assertNoPrivateData(events.snapshot());
    }

    @Test
    void missingOrIllegalActualSubmitHeadersAreConservativeAndCannotBlockDelegate() throws Exception {
        BindingIndex index = bindings();
        int count = 0;
        for (String arguments : List.of("{\"orderId\":" + A + "}",
                "{\"orderId\":" + A + ",\"expectedPolicyCode\":5,\"expectedCatalogFingerprint\":\"fp\"}",
                "{\"orderId\":" + A + ",\"expectedPolicyCode\":\"bad-code\",\"expectedCatalogFingerprint\":\"fp\"}",
                "{\"orderId\":" + A + ",\"expectedPolicyCode\":\"C1\",\"expectedCatalogFingerprint\":\" \"}", "not-json")) {
            SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(directory.resolve("invalid-" + count++)));
            ToolExecutionRequest original = request("submit_refund", arguments);
            ToolExecutionResult result = ok("private " + SECRET);
            McpClient observed = ObservedMcpClient.wrap(fake(passed -> { assertSame(original, passed); return result; }), index, events);
            try (AutoCloseable turn = events.beginTurn("session-a", 0)) { assertSame(result, observed.executeTool(original)); }
            assertFalse(events.evidenceComplete());
            assertFalse(events.awaitObservations(Duration.ofSeconds(1)));
            assertNotNull(events.errorCategory());
            assertNoPrivateData(events.snapshot());
        }
    }

    @Test
    void originalFutureWaitCannotDeclareDelayedObservationComplete() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        CompletableFuture<ToolExecutionResult> original = new CompletableFuture<>();
        McpClient observed = ObservedMcpClient.wrap(async(original), index, events);
        ToolExecutionResult result = ok("[{\"id\":" + A + "}]");
        CompletableFuture<ToolExecutionResult> returned;
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            returned = observed.executeToolAsync(request("list_user_orders", "{}"), null);
        }
        assertSame(original, returned);
        CountDownLatch callerStarted = new CountDownLatch(1), releaseCaller = new CountDownLatch(1);
        returned.whenComplete((reply, error) -> {
            callerStarted.countDown();
            awaitLatch(releaseCaller);
        });
        ExecutorService callbacks = Executors.newFixedThreadPool(2);
        Future<?> completion = callbacks.submit(() -> original.complete(result));
        Future<Boolean> drain = null;
        try {
            assertTrue(callerStarted.await(5, TimeUnit.SECONDS));
            assertSame(result, returned.get(1, TimeUnit.SECONDS));
            assertEquals(0, responses(events).size());
            assertTrue(store.snapshot().isEmpty());
            assertFalse(events.evidenceComplete(), "Original future completion must not certify a pending observer");
            drain = callbacks.submit(() -> events.awaitObservations(Duration.ofSeconds(5)));
            Future<Boolean> waiting = drain;
            assertThrows(TimeoutException.class, () -> waiting.get(20, TimeUnit.MILLISECONDS));
        } finally {
            releaseCaller.countDown();
            completion.get(5, TimeUnit.SECONDS);
            if (drain != null) drain.get(5, TimeUnit.SECONDS);
            callbacks.shutdownNow();
        }
        assertTrue(drain.get(5, TimeUnit.SECONDS));
        assertEquals(2, responses(events).size());
        assertEquals(List.of("order-a"), JSON.convertValue(store.snapshot().get(0).path("aliases"), List.class));
        assertTrue(events.evidenceComplete());
    }

    @Test
    void observationDrainTimeoutRemainsMissingAfterLateBusinessSuccess() throws Exception {
        BindingIndex index = bindings();
        SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(directory));
        CompletableFuture<ToolExecutionResult> original = new CompletableFuture<>();
        McpClient observed = ObservedMcpClient.wrap(async(original), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertSame(original, observed.executeToolAsync(request("get_order", "{\"orderId\":" + A + "}"), null));
        }
        assertFalse(events.awaitObservations(Duration.ZERO));
        assertFalse(events.evidenceComplete());
        assertEquals("MISSING_EVIDENCE", events.errorCategory());
        ToolExecutionResult result = ok("{\"id\":" + A + "}");
        original.complete(result);
        assertSame(result, original.get(1, TimeUnit.SECONDS));
        assertFalse(events.awaitObservations(Duration.ofSeconds(1)), "A late callback cannot erase the failed evidence boundary");
        assertEquals(1, responses(events).size());
        assertEquals("MISSING_EVIDENCE", events.errorCategory());
    }

    @Test
    void observationDrainFailureDoesNotReplaceOriginalFutureOrResult() throws Exception {
        Path blocked = directory.resolve("blocked-sink");
        Files.writeString(blocked, "regular file");
        BindingIndex index = bindings();
        SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(blocked));
        CompletableFuture<ToolExecutionResult> original = new CompletableFuture<>();
        McpClient observed = ObservedMcpClient.wrap(async(original), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertSame(original, observed.executeToolAsync(request("list_user_orders", "{}"), null));
        }
        ToolExecutionResult result = ok("[{\"id\":" + A + "}]");
        original.complete(result);
        assertSame(result, original.get(1, TimeUnit.SECONDS));
        assertFalse(events.awaitObservations(Duration.ofSeconds(1)));
        assertFalse(events.evidenceComplete());
        assertEquals("MISSING_EVIDENCE", events.errorCategory());
        assertEquals(2, responses(events).size());
    }

    @Test
    void interruptedObservationDrainPreservesInterruptAndMarksMissingEvidence() throws Exception {
        BindingIndex index = bindings();
        SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(directory));
        CompletableFuture<ToolExecutionResult> original = new CompletableFuture<>();
        McpClient observed = ObservedMcpClient.wrap(async(original), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            observed.executeToolAsync(request("get_order", "{\"orderId\":" + A + "}"), null);
        }
        Thread.currentThread().interrupt();
        try {
            assertFalse(events.awaitObservations(Duration.ofSeconds(1)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        original.complete(ok("{}"));
        assertEquals("MISSING_EVIDENCE", events.errorCategory());
        assertFalse(events.evidenceComplete());
    }

    @Test
    void exceptionalAsyncResultPreservesOriginalThrowableAndCauseAfterDrain() throws Exception {
        BindingIndex index = bindings();
        SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(directory));
        CompletableFuture<ToolExecutionResult> original = new CompletableFuture<>();
        McpClient observed = ObservedMcpClient.wrap(async(original), index, events);
        CompletableFuture<ToolExecutionResult> returned;
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            returned = observed.executeToolAsync(request("submit_refund", "{\"orderId\":" + A
                    + ",\"expectedPolicyCode\":\"C1\",\"expectedCatalogFingerprint\":\"fp-1\",\"reason\":\"private\"}"), null);
        }
        RuntimeException cause = new RuntimeException("{\"error\":true,\"code\":50005,\"message\":\"" + SECRET + "\"}");
        ToolExecutionException failure = new ToolExecutionException(cause);
        original.completeExceptionally(failure);
        assertSame(original, returned);
        ExecutionException caught = assertThrows(ExecutionException.class, () -> returned.get(1, TimeUnit.SECONDS));
        assertSame(failure, caught.getCause());
        assertSame(cause, caught.getCause().getCause());
        assertTrue(events.awaitObservations(Duration.ofSeconds(1)));
        assertTrue(events.snapshot().stream().anyMatch(event -> event.path("businessCode").asInt() == 50005
                && event.path("status").asText().equals("BUSINESS_ERROR")));
        assertNoPrivateData(events.snapshot());
    }

    @Test
    void twoTargetsAndConcurrentCallbacksKeepTheirActualAliases() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        CountDownLatch observersStarted = new CountDownLatch(2), releaseObservers = new CountDownLatch(1);
        Map<Long, CompletableFuture<ToolExecutionResult>> pending = Map.of(
                A, gatedFuture(observersStarted, releaseObservers), B, gatedFuture(observersStarted, releaseObservers));
        Map<Long, ToolExecutionRequest> passed = new ConcurrentHashMap<>();
        McpClient delegate = (McpClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{McpClient.class}, (proxy, method, arguments) -> {
                    ToolExecutionRequest request = (ToolExecutionRequest) arguments[0];
                    long id = JSON.readTree(request.arguments()).path("orderId").longValue();
                    passed.put(id, request);
                    return pending.get(id);
                });
        McpClient observed = ObservedMcpClient.wrap(delegate, index, events);
        ToolExecutionRequest requestA = request("get_order", "{\"orderId\":" + A + "}");
        ToolExecutionRequest requestB = request("get_order", "{\"orderId\":" + B + "}");
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertSame(pending.get(A), observed.executeToolAsync(requestA, null));
        }
        try (AutoCloseable turn = events.beginTurn("session-b", 2)) {
            assertSame(pending.get(B), observed.executeToolAsync(requestB, null));
        }
        ExecutorService callbacks = Executors.newFixedThreadPool(3);
        Future<?> completeB = callbacks.submit(() -> pending.get(B).complete(ok("{\"id\":" + B + "}")));
        Future<?> completeA = callbacks.submit(() -> pending.get(A).complete(ok("{\"id\":" + A + "}")));
        Future<Boolean> drain = null;
        try {
            assertTrue(observersStarted.await(5, TimeUnit.SECONDS), "Both observation callbacks must be active together");
            pending.get(A).get(1, TimeUnit.SECONDS);
            pending.get(B).get(1, TimeUnit.SECONDS);
            assertFalse(events.evidenceComplete());
            drain = callbacks.submit(() -> events.awaitObservations(Duration.ofSeconds(5)));
            Future<Boolean> waiting = drain;
            assertThrows(TimeoutException.class, () -> waiting.get(20, TimeUnit.MILLISECONDS));
        } finally {
            releaseObservers.countDown();
            completeA.get(5, TimeUnit.SECONDS);
            completeB.get(5, TimeUnit.SECONDS);
            if (drain != null) drain.get(5, TimeUnit.SECONDS);
            callbacks.shutdownNow();
        }
        assertTrue(drain.get(5, TimeUnit.SECONDS));
        assertSame(requestA, passed.get(A));
        assertSame(requestB, passed.get(B));
        assertEquals("order-a", index.orderAlias(A));
        assertEquals("order-b", index.orderAlias(B));
        List<JsonNode> responses = events.snapshot().stream()
                .filter(e -> e.path("status").asText().equals("RESPONSE_RECEIVED")).toList();
        assertEquals(2, responses.size());
        for (JsonNode event : responses) {
            boolean isA = event.path("target").asText().equals("order-a");
            assertEquals(isA ? "session-a" : "session-b", event.path("sessionAlias").asText());
            assertEquals(isA ? 0 : 2, event.path("turnIndex").asInt());
            assertTrue(events.snapshot().stream().anyMatch(start ->
                    start.path("status").asText().equals("CALLED")
                    && start.path("callId").equals(event.path("callId"))
                    && start.path("target").equals(event.path("target"))));
        }
        assertTrue(events.evidenceComplete());
        assertNoPrivateData(events.snapshot());
    }

    @Test
    void unknownTargetFailsAttributionAndGlobalListKeepsReturnedAliases() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        McpClient client = ObservedMcpClient.wrap(fake(request -> switch (request.name()) {
            case "list_user_orders" -> ok("[{\"id\":" + A + "},{\"id\":" + B + "}]");
            case "get_product_detail" -> ok("{\"id\":42}");
            default -> ok("{\"id\":43}");
        }), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            client.executeTool(request("list_user_orders", "{}"));
            client.executeTool(request("get_order", "{\"orderId\":43}"));
            client.executeTool(request("get_product_detail", "{\"productId\":42}"));
        }
        assertTrue(events.snapshot().stream().anyMatch(e -> e.path("tool").asText().equals("list_user_orders")
                && e.path("target").asText().equals("GLOBAL")));
        JsonNode listRecord = store.snapshot().stream().filter(e -> e.path("kind").asText().equals("RETURNED_TARGETS"))
                .findFirst().orElseThrow();
        assertEquals(List.of("order-a", "order-b"), JSON.convertValue(listRecord.path("aliases"), List.class));
        assertTrue(events.snapshot().stream().anyMatch(e -> e.path("target").asText().equals("UNBOUND")));
        assertTrue(events.snapshot().stream().anyMatch(e -> e.path("target").asText().equals("OUT_OF_ALLOWLIST")));
        assertFalse(events.evidenceComplete());
        assertEquals("UNBOUND_TARGET", events.errorCategory());
        assertNoPrivateData(events.snapshot());
    }

    @Test
    void globalOrderListIgnoresStrayIdsAndPreservesReturnedRowAliases() throws Exception {
        assertGlobalEnvelope("list_user_orders", "[{\"id\":" + A + "},{\"id\":" + B + "}]", List.of("order-a", "order-b"));
    }

    @Test
    void globalProductListIgnoresStrayIdsAndPreservesReturnedRowAliases() throws Exception {
        assertGlobalEnvelope("list_on_shelf_products", "{\"records\":[{\"id\":" + PRODUCT + "}]}", List.of("product-a"));
    }

    @Test
    void globalPolicyListIgnoresStrayIds() throws Exception {
        assertGlobalEnvelope("list_policy_clauses", "{\"fingerprint\":\"fp-1\",\"clauses\":[]}", List.of());
    }

    private void assertGlobalEnvelope(String tool, String response, List<String> returnedAliases) throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        ToolExecutionRequest original = request(tool, "{\"orderId\":" + A + ",\"productId\":" + PRODUCT + ",\"pageNum\":1,\"pageSize\":20}");
        ToolExecutionResult result = ok(response);
        McpClient client = ObservedMcpClient.wrap(fake(actual -> { assertSame(original, actual); return result; }), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertSame(result, client.executeTool(original));
        }
        List<JsonNode> recorded = events.snapshot();
        List<String> expectedTargets = new ArrayList<>(List.of("GLOBAL", "GLOBAL"));
        expectedTargets.addAll(returnedAliases);
        assertEquals(expectedTargets, recorded.stream().map(event -> event.path("target").asText()).toList());
        assertEquals("CALLED", recorded.get(0).path("status").asText());
        assertTrue(recorded.stream().skip(1).allMatch(event -> event.path("status").asText().equals("RESPONSE_RECEIVED")));
        assertTrue(recorded.stream().allMatch(event -> event.path("callId").equals(recorded.get(0).path("callId"))));
        List<JsonNode> rows = store.snapshot().stream().filter(record -> record.path("kind").asText().equals("RETURNED_TARGETS")).toList();
        if (tool.equals("list_policy_clauses")) assertTrue(rows.isEmpty());
        else {
            assertEquals(1, rows.size());
            assertEquals(returnedAliases, JSON.convertValue(rows.get(0).path("aliases"), List.class));
            assertEquals(recorded.get(0).path("callId"), rows.get(0).path("callId"));
        }
        assertTrue(events.evidenceComplete());
        assertNull(events.errorCategory());
        assertNoPrivateData(recorded);
    }

    @Test
    void targetedToolsUseOnlyTheirDeclaredIdAndKeepInvalidTargetsUnresolved() throws Exception {
        BindingIndex index = bindings();
        SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(directory));
        McpClient client = ObservedMcpClient.wrap(fake(actual -> ok("{}")), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            for (String tool : List.of("get_order", "get_logistics", "get_refund_eligibility", "submit_refund"))
                client.executeTool(request(tool, "{\"orderId\":" + A + ",\"productId\":" + PRODUCT
                        + (tool.equals("submit_refund") ? ",\"expectedPolicyCode\":\"C1\",\"expectedCatalogFingerprint\":\"fp-1\",\"reason\":\"private\"" : "") + "}"));
            client.executeTool(request("get_product_detail", "{\"orderId\":" + B + ",\"productId\":" + PRODUCT + "}"));
        }
        assertEquals(List.of("order-a", "order-a", "order-a", "order-a", "product-a"), responses(events).stream()
                .map(event -> event.path("target").asText()).toList());
        assertTrue(events.evidenceComplete());
        try (AutoCloseable turn = events.beginTurn("session-a", 1)) {
            client.executeTool(request("get_order", "{\"orderId\":\"" + A + "\",\"productId\":" + PRODUCT + "}"));
            client.executeTool(request("get_product_detail", "{\"orderId\":" + A + "}"));
        }
        assertEquals(List.of("UNBOUND", "UNBOUND", "OUT_OF_ALLOWLIST", "OUT_OF_ALLOWLIST"), events.snapshot().stream()
                .filter(event -> event.path("turnIndex").asInt() == 1).map(event -> event.path("target").asText()).toList());
        assertFalse(events.evidenceComplete());
        assertEquals("UNBOUND_TARGET", events.errorCategory());
        assertNoPrivateData(events.snapshot());
    }

    @Test
    void reviewParsingFailureIsObservedWithoutExceptionMessage() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        AtomicInteger returned = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                returned.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from(SECRET)).build();
            }
        };
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertNull(AgentConfig.reviewSafely(AgentConfig.reviewAgent(model), context(), events));
        }
        assertEquals(1, returned.get());
        JsonNode failure = events.snapshot().stream().filter(e -> e.path("phase").asText().equals("REVIEW")
                && e.path("status").asText().equals("FAILED")).findFirst().orElseThrow();
        assertTrue(failure.path("exceptionClass").asText().contains("Exception"));
        assertEquals("REVIEW_FORMAT_ERROR", events.errorCategory());
        assertFalse(events.snapshot().toString().contains(SECRET));
        assertFalse(store.snapshot().toString().contains(SECRET));
    }

    @Test
    void observerFailureCannotAuthorizeOrChangeBusinessResult() throws Exception {
        FlowObserver broken = new FlowObserver() {
            @Override public void onEvent(String phase, Long id, Map<String, Object> attributes) {
                throw new IllegalStateException(SECRET);
            }
            @Override public void onSourceEvidence(String key, String text, String digest) {
                throw new IllegalStateException(SECRET);
            }
        };
        AtomicInteger submissions = new AtomicInteger();
        EscalationTools escalation = new EscalationTools("session-a", ignored -> {});
        RefundWorkflow approved = new RefundWorkflow(refundFacts(), c -> approval(),
                (id, reason, fingerprint, code) -> { submissions.incrementAndGet(); return "trusted-receipt"; },
                escalation, broken);
        assertEquals("trusted-receipt", approved.apply("session-a", refundRequest()));
        RefundWorkflow denied = new RefundWorkflow(refundFacts(), c -> null,
                (id, reason, fingerprint, code) -> { submissions.incrementAndGet(); return "wrong"; },
                escalation, broken);
        assertTrue(denied.apply("session-b", refundRequest()).contains("未执行"));
        assertEquals(1, submissions.get());
        assertNull(AgentConfig.reviewSafely(input -> { throw new IllegalArgumentException(SECRET); }, context(), broken));
        assertEquals(approval(), AgentConfig.reviewSafely(input -> approval(), context(), broken));

        // A real recorder whose private sink is unavailable must retain a missing-evidence signal.
        Path blockedPath = directory.resolve("regular-file");
        Files.writeString(blockedPath, "blocked");
        SafeEventRecorder damaged = recorder(bindings(), new PrivateEvidenceStore(blockedPath));
        try (AutoCloseable turn = damaged.beginTurn("session-a", 0)) {
            damaged.onSourceEvidence("FAQ-001", "private source body", "a".repeat(64));
        }
        assertFalse(damaged.evidenceComplete());
        assertEquals("MISSING_EVIDENCE", damaged.errorCategory());
        assertFalse(damaged.snapshot().toString().contains(SECRET));
    }

    @Test
    void mcpErrorsPreserveIdentityAndStaleReviewHandling() throws Exception {
        BindingIndex index = bindings();
        SafeEventRecorder events = recorder(index, new PrivateEvidenceStore(directory));
        RuntimeException cause = new RuntimeException("{\"error\":true,\"code\":50005,\"message\":\"" + SECRET + "\"}");
        ToolExecutionException original = new ToolExecutionException(cause);
        McpClient wrapped = ObservedMcpClient.wrap(fake(request -> { throw original; }), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            ToolExecutionException caught = assertThrows(ToolExecutionException.class,
                    () -> wrapped.executeTool(request("submit_refund", "{\"orderId\":" + A + "}")));
            assertSame(original, caught);
            assertSame(cause, caught.getCause());
            assertThrows(RefundExecutor.StaleReviewException.class,
                    () -> new RefundExecutor(wrapped).apply(A, "reason", "fp-1", "C1"));
        }
        assertTrue(events.snapshot().stream().anyMatch(e -> e.path("businessCode").asInt() == 50005));
        assertFalse(events.snapshot().toString().contains(SECRET));
        ToolExecutionResult originalResult = ok("private result " + SECRET);
        McpClient success = ObservedMcpClient.wrap(fake(request -> originalResult), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 1)) {
            assertSame(originalResult, success.executeTool(request("get_order", "{\"orderId\":" + A + "}")));
        }
    }

    @Test
    void confirmationCancellationFlowAndReplyKindsComeFromActualBranches() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        RefundHandoffTools handoff = new RefundHandoffTools();
        EscalationTools escalation = new EscalationTools("session-a", ignored -> {});
        RefundWorkflow flow = new RefundWorkflow(refundFacts(), c -> approval(), (id, reason, fp, code) -> "receipt",
                escalation, events);
        ConversationCoordinator coordinator = new ConversationCoordinator((session, input) -> {
            if (input.contains("退款")) handoff.handoffRefund(B, "未收到货");
            return "model prose";
        }, handoff, escalation, () -> List.of(A, B), id -> "eligibility", id -> false, flow::apply, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            coordinator.handleTurn("session-a", "订单 " + A + " 请退款，原因：未收到货");
        }
        try (AutoCloseable turn = events.beginTurn("session-a", 1)) {
            assertEquals("receipt", coordinator.handleTurn("session-a", "/confirm-refund " + A));
        }
        try (AutoCloseable turn = events.beginTurn("session-a", 2)) {
            coordinator.handleTurn("session-a", "订单 " + B + " 请退款，原因：未收到货");
            coordinator.handleTurn("session-a", "/cancel-refund");
            coordinator.handleTurn("session-a", "你好");
        }
        assertTrue(events.snapshot().stream().anyMatch(e -> e.path("phase").asText().equals("CONFIRMATION")
                && e.path("status").asText().equals("COMPLETED") && e.path("target").asText().equals("order-a")));
        assertTrue(events.snapshot().stream().anyMatch(e -> e.path("phase").asText().equals("CONFIRMATION")
                && e.path("status").asText().equals("SKIPPED") && e.path("target").asText().equals("order-b")));
        for (String phase : List.of("FACTS", "POLICY", "REVIEW", "EXECUTION")) {
            assertTrue(events.snapshot().stream().anyMatch(e -> e.path("phase").asText().equals(phase)
                    && e.path("status").asText().equals("COMPLETED")), phase);
        }
        assertTrue(store.snapshot().stream().anyMatch(e -> e.path("replyKind").asText().equals("FREE_TEXT")));
        assertTrue(store.snapshot().stream().anyMatch(e -> e.path("replyKind").asText().equals("TRUSTED_TEMPLATE")));
        assertNoPrivateData(events.snapshot());
    }

    @Test
    void sourceAndFinalReplyTextStayPrivateAndUnknownAttributesAreNotExported() throws Exception {
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(bindings(), store);
        ExplanationService explanation = new ExplanationService(fake(request -> { throw new AssertionError(); }), Set.of(),
                input -> { throw new IllegalArgumentException(SECRET); }, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            String answer = explanation.answer("如何查看物流？", null);
            assertFalse(answer.contains("无可靠依据"));
            String relative = store.writeFinalReply("session-a", 0, answer + SECRET);
            assertFalse(Path.of(relative).isAbsolute());
            assertTrue(Files.readString(directory.resolve(relative)).contains(SECRET));
            assertTrue(store.snapshot().stream().anyMatch(record -> record.path("kind").asText().equals("FINAL_REPLY")
                    && record.path("file").asText().equals(relative)));
        }
        assertTrue(store.snapshot().stream().anyMatch(e -> e.path("kind").asText().equals("SOURCE")));
        assertTrue(store.snapshot().stream().anyMatch(e -> e.path("replyKind").asText().equals("SOURCE_ORIGINAL")));
        assertFalse(events.snapshot().toString().contains("private source body"));
        assertNoPrivateData(events.snapshot());
        try (AutoCloseable turn = events.beginTurn("session-a", 1)) {
            events.onEvent("SESSION", null, Map.of("status", "COMPLETED", "rawReply", SECRET));
        }
        assertFalse(events.evidenceComplete());
        assertEquals("MISSING_EVIDENCE", events.errorCategory());
        assertFalse(events.snapshot().toString().contains(SECRET));
    }

    @Test
    void observerAssertionFailureDoesNotChangeResultOrSafeReviewNullHandling() {
        FlowObserver broken = new FlowObserver() {
            public void onEvent(String phase, Long targetId, Map<String, Object> attributes) { throw new AssertionError(SECRET); }
            public void onSourceEvidence(String key, String text, String digest) { throw new AssertionError(SECRET); }
        };
        assertEquals(approval(), AgentConfig.reviewSafely(input -> approval(), context(), broken));
        assertEquals("receipt", new RefundWorkflow(refundFacts(), input -> approval(),
                (id, reason, fingerprint, code) -> "receipt", new EscalationTools("session-a", ignored -> {}), broken)
                .apply("session-a", refundRequest()));
        assertNull(AgentConfig.reviewSafely(input -> approval(), null, broken));
    }

    @Test
    void directReviewObservesStructuredOutcomeWithoutWorkflowAndNeverReturnsAReplacementVerdict() throws Exception {
        SafeEventRecorder events = recorder(bindings(), new PrivateEvidenceStore(directory));
        ReviewVerdict verdict = approval();
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertSame(verdict, AgentConfig.reviewSafely(input -> verdict, context(), events));
        }
        assertTrue(events.snapshot().stream().anyMatch(event -> event.path("phase").asText().equals("REVIEW")
                && event.path("status").asText().equals("STARTED")));
        assertTrue(events.snapshot().stream().anyMatch(event -> event.path("phase").asText().equals("REVIEW")
                && event.path("status").asText().equals("COMPLETED") && event.path("target").asText().equals("order-a")));
    }

    @Test
    void freshProductCitationUsesLogicalSourceKeyAndRejectedRereadDisablesSourceReply() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        AtomicInteger reads = new AtomicInteger();
        McpClient products = fake(request -> switch (request.name()) {
            case "list_on_shelf_products" -> ok("{\"records\":[{\"id\":" + PRODUCT
                    + ",\"name\":\"亚麻袋\",\"description\":\"亚麻资料\",\"status\":\"ON_SHELF\"}],\"total\":1,\"size\":20,\"current\":1}");
            case "get_product_detail" -> {
                int read = reads.incrementAndGet();
                yield ok("{\"id\":" + PRODUCT + ",\"name\":\"亚麻袋\",\"description\":\"亚麻资料\",\"status\":\""
                        + (read == 4 ? "OFF_SHELF" : "ON_SHELF") + "\",\"skus\":[{\"id\":123,\"specs\":\"米色\",\"price\":12.50,\"stock\":4}]}");
            }
            default -> throw new AssertionError(request.name());
        });
        ExplanationService explanation = new ExplanationService(products, Set.of(PRODUCT),
                input -> new ExplanationDraft("请以所引资料原文为准。", List.of("PRODUCT-" + PRODUCT)), events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertTrue(explanation.answer("亚麻袋商品价格？", null).contains("[PRODUCT-" + PRODUCT + "]"));
        }
        assertTrue(events.snapshot().stream().anyMatch(event -> event.path("target").asText().equals("product-a")
                && event.path("status").asText().equals("COMPLETED") && event.path("sourceKey").asText().equals("PRODUCT:product-a")));
        int sourceCount = (int) store.snapshot().stream().filter(record -> record.path("kind").asText().equals("SOURCE")).count();
        try (AutoCloseable turn = events.beginTurn("session-a", 1)) {
            assertTrue(explanation.answer("亚麻袋商品价格？", null).contains("无可靠依据"));
        }
        assertEquals(sourceCount, store.snapshot().stream().filter(record -> record.path("kind").asText().equals("SOURCE")).count());
        assertTrue(events.snapshot().stream().anyMatch(event -> event.path("target").asText().equals("product-a")
                && event.path("status").asText().equals("REJECTED")));
        assertNoPrivateData(events.snapshot());
        assertTrue(events.evidenceComplete());
    }

    @Test
    void globalCatalogRowsOutsideAllowlistAreObservedWithoutMakingTheTraversalAnAttributionFailure() throws Exception {
        BindingIndex index = bindings();
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder events = recorder(index, store);
        ToolExecutionResult result = ok("{\"records\":[{\"id\":" + PRODUCT + "},{\"id\":42}]}");
        McpClient client = ObservedMcpClient.wrap(fake(request -> result), index, events);
        try (AutoCloseable turn = events.beginTurn("session-a", 0)) {
            assertSame(result, client.executeTool(request("list_on_shelf_products", "{\"pageNum\":1,\"pageSize\":20}")));
        }
        assertTrue(events.snapshot().stream().anyMatch(event -> event.path("target").asText().equals("OUT_OF_ALLOWLIST")));
        assertTrue(store.snapshot().stream().anyMatch(record -> record.path("aliases").toString().equals("[\"product-a\",\"OUT_OF_ALLOWLIST\"]")));
        assertTrue(events.evidenceComplete());
        assertNull(events.errorCategory());
        try (AutoCloseable turn = events.beginTurn("session-a", 1)) {
            events.onSourceEvidence("PRODUCT-42", "must not become an admitted source", "a".repeat(64));
        }
        assertFalse(events.evidenceComplete());
        assertEquals("MISSING_EVIDENCE", events.errorCategory());
    }

    private static void assertNoPrivateData(List<JsonNode> events) {
        String text = events.toString();
        for (String privateValue : List.of(Long.toString(A), Long.toString(B), Long.toString(PRODUCT), "private-a", SECRET))
            assertFalse(text.contains(privateValue), text);
        for (JsonNode event : events) {
            assertFalse(event.has("attributes"));
            assertFalse(event.has("replyKind"));
            assertFalse(event.has("aliases"));
            assertTrue(event.has("totalTokens") && event.path("totalTokens").isNull());
        }
    }

    private static McpClient fake(Function<ToolExecutionRequest, ToolExecutionResult> call) {
        return (McpClient) Proxy.newProxyInstance(ObservationTest.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("executeTool")) return call.apply((ToolExecutionRequest) arguments[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static McpClient async(CompletableFuture<ToolExecutionResult> future) {
        return (McpClient) Proxy.newProxyInstance(ObservationTest.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, arguments) -> future);
    }

    private static CompletableFuture<ToolExecutionResult> gatedFuture(CountDownLatch started, CountDownLatch release) {
        return new CompletableFuture<>() {
            @Override public CompletableFuture<ToolExecutionResult> whenComplete(BiConsumer<? super ToolExecutionResult, ? super Throwable> observer) {
                return super.whenComplete((reply, failure) -> {
                    started.countDown();
                    awaitLatch(release);
                    observer.accept(reply, failure);
                });
            }
        };
    }

    private static List<JsonNode> responses(SafeEventRecorder events) {
        return events.snapshot().stream().filter(event -> event.path("status").asText().equals("RESPONSE_RECEIVED")).toList();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static McpClient refundFacts() {
        return fake(request -> switch (request.name()) {
            case "get_order" -> ok("{\"id\":" + A + ",\"status\":\"SHIPPED\",\"totalAmount\":99.00}");
            case "get_refund_eligibility" -> ok("{\"orderId\":" + A + ",\"orderStatus\":\"SHIPPED\",\"eligible\":true,"
                    + "\"refundExists\":false,\"refundableAmount\":99.0,\"catalogFingerprint\":\"fp-1\",\"policyCode\":\"C1\",\"policyTitle\":\"未签收\"}");
            case "list_policy_clauses" -> ok("{\"fingerprint\":\"fp-1\",\"clauses\":[{\"code\":\"C1\",\"title\":\"未签收\",\"clauseText\":\"未签收可核验退款。\"}]}");
            default -> throw new AssertionError(request.name());
        });
    }
    private static RefundRequest refundRequest() { return new RefundRequest(A, "未收到货", "订单 " + A + " 请退款，原因：未收到货"); }
    private static RefundReviewContext context() { return new RefundReviewContext("input", "order", "eligible",
            new CandidateRefundAction(A, "reason"), new PolicyEvidence("fp-1", "C1", "title", "clause")); }
    private static ReviewVerdict approval() { return new ReviewVerdict(true, "C1", List.of()); }
    private static ToolExecutionRequest request(String name, String arguments) { return ToolExecutionRequest.builder().name(name).arguments(arguments).build(); }
    private static ToolExecutionResult ok(String text) { return ToolExecutionResult.builder().resultText(text).isError(false).build(); }
}
