package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.flow.FlowObserver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Delegates SDK calls unchanged; only the observation side parses private projections. */
public final class ObservedMcpClient {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper ERROR_JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private ObservedMcpClient() { }

    public static McpClient wrap(McpClient delegate, BindingIndex index, SafeEventRecorder recorder) {
        Objects.requireNonNull(delegate); Objects.requireNonNull(index); Objects.requireNonNull(recorder);
        AtomicLong calls = new AtomicLong();
        return (McpClient) Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("executeTool") && !method.getName().equals("executeToolAsync")) {
                        try { return method.invoke(delegate, arguments); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    }
                    ToolExecutionRequest request = (ToolExecutionRequest) arguments[0];
                    SafeEventRecorder.Turn origin = recorder.captureTurn();
                    String callId = "mcp-" + calls.incrementAndGet();
                    final Long actual = actualTarget(request);
                    Map<String, Object> base = attributes(request, callId, recorder);
                    long started = System.nanoTime();
                    CompletableFuture<Void> observation = recorder.observationStarted();
                    boolean deferred = false;
                    try {
                        event(recorder, origin, actual, base, "CALLED", 0L, null, null);
                        final Object result;
                        try { result = method.invoke(delegate, arguments); }
                        catch (InvocationTargetException error) {
                            observeError(recorder, origin, actual, base, started, error.getCause());
                            throw error.getCause();
                        }
                        if (result instanceof CompletableFuture<?> future) {
                            deferred = true;
                            // Track the observation separately and return the delegate's ORIGINAL future.
                            try {
                                future.whenComplete((reply, error) -> {
                                    try {
                                        if (error != null) observeError(recorder, origin, actual, base, started, error);
                                        else observeResult(recorder, index, origin, request, actual, base, started, (ToolExecutionResult) reply);
                                    } catch (Throwable failure) { recorder.onObservationFailure(failure); }
                                    finally { recorder.observationFinished(observation); }
                                });
                            } catch (Throwable failure) {
                                recorder.onObservationFailure(failure);
                                recorder.observationFinished(observation);
                            }
                        } else observeResult(recorder, index, origin, request, actual, base, started, (ToolExecutionResult) result);
                        return result;
                    } finally { if (!deferred) recorder.observationFinished(observation); }
                });
    }

    private static Long actualTarget(ToolExecutionRequest request) {
        String field = switch (request.name()) {
            case "get_order", "get_logistics", "get_refund_eligibility", "submit_refund" -> "orderId";
            case "get_product_detail" -> "productId";
            default -> null;
        };
        // Global tools have no target argument; unrelated JSON members cannot create one.
        if (field == null) return null;
        try {
            JsonNode args = JSON.readTree(request.arguments());
            Long target = id(args == null ? null : args.get(field));
            return target == null ? 0L : target;
        } catch (Exception ignored) { return 0L; }
    }
    private static Long id(JsonNode node) {
        if (node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0) return node.longValue();
        return null;
    }
    private static Map<String, Object> attributes(ToolExecutionRequest request, String callId, SafeEventRecorder recorder) {
        Map<String, Object> result = new HashMap<>();
        result.put("role", "MCP"); result.put("tool", request.name()); result.put("callId", callId);
        result.put("targetKind", request.name().equals("get_product_detail") || request.name().equals("list_on_shelf_products") ? "PRODUCT" : "ORDER");
        if (request.name().equals("submit_refund")) {
            // These are the actual request headers, not the workflow's intended values.
            // Raw arguments (including reason) never enter either evidence projection.
            try {
                JsonNode arguments = JSON.readTree(request.arguments());
                if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException();
                JsonNode code = arguments.get("expectedPolicyCode"), fingerprint = arguments.get("expectedCatalogFingerprint");
                if (code == null || !code.isTextual() || !code.textValue().matches("[A-Z][A-Z0-9_-]{0,63}")
                        || fingerprint == null || !fingerprint.isTextual() || fingerprint.textValue().isBlank()
                        || fingerprint.textValue().codePoints().anyMatch(c -> c < 32)) throw new IllegalArgumentException();
                result.put("policyCode", code.textValue());
                result.put("policyFingerprint", fingerprint.textValue());
            } catch (Throwable failure) {
                recorder.onObservationFailure(failure);
            }
        }
        return result;
    }
    private static void observeResult(SafeEventRecorder recorder, BindingIndex index, SafeEventRecorder.Turn origin,
                                      ToolExecutionRequest request, Long target, Map<String, Object> base, long started,
                                      ToolExecutionResult result) {
        try {
            String text = result == null ? null : result.resultText();
            JsonNode body = parse(text);
            Integer businessCode = businessCode(body);
            event(recorder, origin, target, base, result == null ? "FAILED" : result.isError() ? "BUSINESS_ERROR" : "RESPONSE_RECEIVED",
                    elapsed(started), businessCode, null);
            if (origin != null && origin.sessionAlias().equals("mcp-contract")) {
                if (text == null) recorder.onObservationFailure(new IllegalStateException());
                else recorder.sourceEvidence(origin, (String) base.get("callId"), "MCP_RESULT", text, FlowObserver.textDigest(text));
            } else if (result != null && !result.isError() && request.name().equals("get_product_detail")
                    && !index.productAlias(target).equals("OUT_OF_ALLOWLIST")) {
                String canonical = canonicalProduct(body, target);
                if (canonical != null) recorder.sourceEvidence(origin, (String) base.get("callId"),
                        "PRODUCT_CANONICAL:" + index.productAlias(target), canonical, FlowObserver.textDigest(canonical));
            }
            if (result == null || result.isError()) return;
            if (request.name().equals("list_user_orders") || request.name().equals("list_on_shelf_products")) {
                JsonNode rows = body != null && body.isArray() ? body : body == null ? null : body.get("records");
                if (rows == null || !rows.isArray()) { recorder.onObservationFailure(new IllegalStateException()); return; }
                List<String> aliases = new ArrayList<>();
                boolean product = request.name().equals("list_on_shelf_products");
                for (JsonNode row : rows) {
                    Long returned = id(row.get("id"));
                    if (returned == null) returned = 0L;
                    aliases.add(product ? index.productAlias(returned) : index.orderAlias(returned));
                    event(recorder, origin, returned, base, "RESPONSE_RECEIVED", elapsed(started), null, null);
                }
                recorder.returnedTargets(origin, (String) base.get("callId"), aliases);
            }
        } catch (Throwable failure) { recorder.onObservationFailure(failure); }
    }

    // Same canonical fields, returned SKU order and decimal conversion as CurrentProductIndex.digest.
    // Only the admitted product projection is retained; arbitrary response fields are excluded.
    private static String canonicalProduct(JsonNode detail, Long requested) {
        if (detail == null || !detail.isObject() || !Objects.equals(id(detail.get("id")), requested)
                || !nonblank(detail.get("name")) || !nonblank(detail.get("description"))
                || !Set.of("DRAFT", "ON_SHELF", "OFF_SHELF").contains(detail.path("status").asText())
                || !detail.path("skus").isArray()) throw new IllegalArgumentException("Invalid product projection");
        ObjectNode content = JSON.createObjectNode().put("productId", requested)
                .put("name", detail.path("name").textValue()).put("description", detail.path("description").textValue());
        var skus = content.putArray("skus");
        Set<Long> seen = new HashSet<>();
        for (JsonNode sku : detail.path("skus")) {
            Long skuId = id(sku.get("id"));
            JsonNode price = sku.get("price"), stock = sku.get("stock");
            if (skuId == null || !seen.add(skuId) || !nonblank(sku.get("specs")) || price == null || !price.isNumber()
                    || price.decimalValue().signum() < 0 || stock == null || !stock.isIntegralNumber()
                    || !stock.canConvertToLong() || stock.longValue() < 0) throw new IllegalArgumentException("Invalid SKU projection");
            skus.addObject().put("id", skuId).put("specs", sku.path("specs").textValue())
                    .put("price", price.decimalValue().toPlainString()).put("stock", stock.longValue());
        }
        return detail.path("status").asText().equals("ON_SHELF") ? content.toString() : null;
    }

    private static boolean nonblank(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }
    private static void observeError(SafeEventRecorder recorder, SafeEventRecorder.Turn origin, Long target,
                                     Map<String, Object> base, long started, Throwable error) {
        try {
            Integer code = null;
            if (error instanceof ToolExecutionException toolError && toolError.errorCode() == null) {
                Throwable cause = toolError.getCause();
                if (cause != null && cause.getClass() == RuntimeException.class && cause.getCause() == null)
                    code = businessCode(errorBody(cause.getMessage()));
            }
            event(recorder, origin, target, base, code == null ? "TRANSPORT_ERROR" : "BUSINESS_ERROR", elapsed(started), code, error);
            if (code != null && origin != null && origin.sessionAlias().equals("mcp-contract")
                    && "get_order".equals(base.get("tool"))) {
                String raw = businessErrorText(error);
                recorder.sourceEvidence(origin, (String)base.get("callId"), "MCP_RESULT", raw, FlowObserver.textDigest(raw));
            }
        } catch (Throwable failure) { recorder.onObservationFailure(failure); }
    }
    private static void event(SafeEventRecorder recorder, SafeEventRecorder.Turn origin, Long target, Map<String, Object> base,
                              String status, long duration, Integer code, Throwable error) {
        Map<String, Object> attributes = new HashMap<>(base);
        attributes.put("status", status); attributes.put("durationMs", duration);
        if (code != null) attributes.put("businessCode", code);
        if (error != null) attributes.put("exceptionClass", error.getClass().getName());
        recorder.record(origin, "MCP", target, attributes);
    }
    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private static JsonNode parse(String value) {
        try { return value == null ? null : JSON.readTree(value); }
        catch (Exception ignored) { return null; }
    }
    private static Integer businessCode(JsonNode node) {
        return node != null && node.isObject() && node.size() == 3 && node.path("error").isBoolean()
                && node.path("error").booleanValue() && node.path("code").isIntegralNumber()
                && node.path("code").canConvertToInt() && node.path("code").intValue() >= 0
                && node.path("message").isTextual() ? node.path("code").intValue() : null;
    }

    private static String businessErrorText(Throwable error) {
        if (!(error instanceof ToolExecutionException sdk) || sdk.errorCode() != null) return null;
        Throwable cause = sdk.getCause();
        if (cause == null || cause.getClass() != RuntimeException.class || cause.getCause() != null) return null;
        return businessCode(errorBody(cause.getMessage())) == null ? null : cause.getMessage();
    }

    private static JsonNode errorBody(String raw) {
        try { return raw == null ? null : ERROR_JSON.readTree(raw); }
        catch (Exception invalid) { return null; }
    }

    /** Only a body already recorded by this delegated, bound SDK call may become a direct final. */
    static String matchedBusinessError(ToolExecutionRequest request, Throwable error, BindingIndex index,
                                      SafeEventRecorder recorder, PrivateEvidenceStore store) {
        String raw = businessErrorText(error);
        SafeEventRecorder.Turn origin = recorder.captureTurn();
        if (raw == null || origin == null || !origin.sessionAlias().equals("mcp-contract")
                || !request.name().equals("get_order")) return null;
        String alias = index.orderAlias(actualTarget(request));
        if (alias.equals("UNBOUND") || alias.equals("GLOBAL")) return null;
        List<JsonNode> calls = recorder.snapshot().stream().filter(e -> e.path("phase").asText().equals("MCP")
                && e.path("tool").asText().equals("get_order") && e.path("status").asText().equals("BUSINESS_ERROR")
                && e.path("target").asText().equals(alias) && e.path("sessionAlias").asText().equals(origin.sessionAlias())
                && e.path("turnIndex").asInt() == origin.turnIndex()).toList();
        if (calls.isEmpty()) return null;
        JsonNode call = calls.get(calls.size() - 1);
        return store.snapshot().stream().anyMatch(r -> r.path("kind").asText().equals("SOURCE")
                && r.path("sourceKey").asText().equals("MCP_RESULT") && r.path("callId").asText().equals(call.path("callId").asText())
                && r.path("sessionAlias").asText().equals(origin.sessionAlias()) && r.path("turnIndex").asInt() == origin.turnIndex()
                && r.path("text").asText().equals(raw) && r.path("sourceDigest").asText().equals(FlowObserver.textDigest(raw))) ? raw : null;
    }
}
