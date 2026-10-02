package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            JsonNode body = parse(result == null ? null : result.resultText());
            Integer businessCode = businessCode(body);
            event(recorder, origin, target, base, result == null ? "FAILED" : result.isError() ? "BUSINESS_ERROR" : "RESPONSE_RECEIVED",
                    elapsed(started), businessCode, null);
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
    private static void observeError(SafeEventRecorder recorder, SafeEventRecorder.Turn origin, Long target,
                                     Map<String, Object> base, long started, Throwable error) {
        try {
            Integer code = null;
            if (error instanceof ToolExecutionException toolError && toolError.errorCode() == null) {
                Throwable cause = toolError.getCause();
                if (cause != null && cause.getClass() == RuntimeException.class && cause.getCause() == null)
                    code = businessCode(parse(cause.getMessage()));
            }
            event(recorder, origin, target, base, code == null ? "TRANSPORT_ERROR" : "BUSINESS_ERROR", elapsed(started), code, error);
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
}
