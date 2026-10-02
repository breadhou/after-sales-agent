package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.mall.agent.flow.FlowObserver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Evaluation-only substitutions, installed above actual provider/MCP instrumentation. */
public final class ControlledAdapters {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z][a-z0-9-]{0,63})}}");
    private ControlledAdapters() { }

    public static ChatModel model(ChatModel delegate, JsonNode control) {
        return model(delegate, control, control.path("script").path("role").asText(), FlowObserver.NOOP);
    }

    static ChatModel model(ChatModel delegate, JsonNode control, String role, FlowObserver observer) {
        return model(delegate, control, role, observer, null);
    }

    static ChatModel model(ChatModel delegate, JsonNode control, String role, FlowObserver observer, JsonNode bindings) {
        if (!"MODEL_SCRIPT".equals(control.path("point").asText())
                || !role.equals(control.path("script").path("role").asText())) return delegate;
        if (!"SUBSTITUTED".equals(control.path("components").path(role).asText()))
            throw new IllegalArgumentException("Invalid scripted role");
        JsonNode responses = control.path("script").path("responses").deepCopy();
        AtomicInteger next = new AtomicInteger();
        return new ForwardingModel(delegate) {
            private ChatResponse scripted() {
                int step = next.getAndIncrement();
                if (step >= responses.size()) {
                    FlowObserver.event(observer, phase(role), null, Map.of("role", role, "status", "FAILED", "errorCategory", "MODEL_ERROR"));
                    throw new IllegalStateException("MODEL_ERROR");
                }
                FlowObserver.event(observer, "MODEL", null, Map.of("role", role, "status", "SCRIPTED", "callId", "script-"+role.toLowerCase(java.util.Locale.ROOT)+"-"+step));
                JsonNode response = responses.get(step);
                List<ToolExecutionRequest> calls = new ArrayList<>();
                int i = 0;
                for (JsonNode call : response.path("toolCalls")) calls.add(ToolExecutionRequest.builder()
                        .id("script-tool-"+step+"-"+(i++)).name(call.path("name").textValue())
                        .arguments(bindScriptArguments(call.path("arguments"), bindings).toString()).build());
                String text = response.has("text") ? response.path("text").textValue() : null;
                AiMessage message = calls.isEmpty() ? new AiMessage(text) : text == null
                        ? AiMessage.from(calls) : AiMessage.from(text, calls);
                return ChatResponse.builder().aiMessage(message).build();
            }
            @Override public ChatResponse chat(ChatRequest request) { return scripted(); }
            @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) { return scripted(); }
        };
    }

    /** Exact declared business aliases only; arbitrary untrusted script shapes remain intact. */
    private static JsonNode bindScriptArguments(JsonNode value, JsonNode bindings) {
        if (bindings != null && value.isTextual()) {
            Matcher match = PLACEHOLDER.matcher(value.textValue());
            if (match.matches()) {
                String alias = match.group(1);
                if (bindings.path("orders").has(alias)) return LongNode.valueOf(Long.parseLong(bindings.path("orders").path(alias).path("orderId").textValue()));
                if (bindings.path("products").has(alias)) return LongNode.valueOf(Long.parseLong(bindings.path("products").path(alias).path("productId").textValue()));
            }
        }
        if (value.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            value.fields().forEachRemaining(field -> copy.set(field.getKey(), bindScriptArguments(field.getValue(), bindings)));
            return copy;
        }
        if (value.isArray()) {
            ArrayNode copy = JSON.createArrayNode(); value.forEach(item -> copy.add(bindScriptArguments(item, bindings))); return copy;
        }
        return value.deepCopy();
    }

    public static McpClient mcp(McpClient delegate, JsonNode control, FlowObserver observer) {
        String point = control.path("point").asText();
        if (Set.of("MODEL_SCRIPT", "NONE").contains(point)) return delegate;
        return (McpClient)Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, arguments) -> {
                    if (!Set.of("executeTool", "executeToolAsync").contains(method.getName())) return invoke(delegate, method, arguments);
                    ToolExecutionRequest request = (ToolExecutionRequest)arguments[0];
                    boolean selected = point.equals("SOURCE_BEFORE_FINAL")
                            ? finalProductRead(request, observer) : request.name().equals(control.path("toolName").asText());
                    if (!selected) return invoke(delegate, method, arguments);
                    Long target = target(request);
                    Map<String, Object> attributes = Map.of("role", "MCP", "status", "SCRIPTED", "tool", request.name(),
                            "targetKind", request.name().equals("get_product_detail") ? "PRODUCT" : "ORDER");
                    SafeEventRecorder recorder = observer instanceof SafeEventRecorder r ? r : null;
                    SafeEventRecorder.Turn origin = recorder == null ? null : recorder.captureTurn();
                    Runnable scripted = () -> {
                        if (recorder == null) FlowObserver.event(observer, "MCP", target, attributes);
                        else recorder.record(origin, "MCP", target, attributes);
                    };
                    if (point.equals("MCP_BEFORE_REQUEST")) {
                        scripted.run();
                        throw new InjectedFailure();
                    }
                    if (point.equals("MCP_RESPONSE") || point.equals("SOURCE_BEFORE_FINAL")) {
                        scripted.run();
                        ToolExecutionResult result = ToolExecutionResult.builder().isError(false)
                                .resultText(control.path("response").textValue()).build();
                        return method.getName().equals("executeToolAsync") ? CompletableFuture.completedFuture(result) : result;
                    }
                    CompletableFuture<Void> pending = recorder == null ? null : recorder.observationStarted();
                    boolean deferred = false;
                    try {
                        Object result = invoke(delegate, method, arguments);
                        if (point.equals("MCP_AFTER_RESPONSE")) {
                            if (result instanceof CompletableFuture<?> future) {
                                CompletableFuture<?> lost = future.thenApply(ignored -> { scripted.run(); throw new InjectedFailure(); });
                                if (recorder != null) lost.whenComplete((reply, failure) -> recorder.observationFinished(pending));
                                deferred = true;
                                return lost;
                            }
                            scripted.run();
                            throw new InjectedFailure();
                        }
                        throw new IllegalArgumentException("Unsupported control point");
                    } finally { if (!deferred && recorder != null) recorder.observationFinished(pending); }
                });
    }

    private static boolean finalProductRead(ToolExecutionRequest request, FlowObserver observer) {
        if (!request.name().equals("get_product_detail") || !(observer instanceof SafeEventRecorder recorder)) return false;
        List<JsonNode> events = recorder.snapshot();
        if (events.isEmpty()) return false;
        JsonNode last = events.get(events.size()-1);
        // Production emits this checkpoint immediately before each citation's final reread.
        return last.path("phase").asText().equals("EXPLANATION") && last.path("status").asText().equals("STARTED")
                && last.path("sourceKey").asText().startsWith("PRODUCT:");
    }

    static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static Long target(ToolExecutionRequest request) {
        String field = switch (request.name()) {
            case "get_order", "get_logistics", "get_refund_eligibility", "submit_refund" -> "orderId";
            case "get_product_detail" -> "productId";
            default -> null;
        };
        if (field == null) return null;
        try { JsonNode value = JSON.readTree(request.arguments()).path(field); return value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>0 ? value.longValue() : 0L; }
        catch (Exception ignored) { return 0L; }
    }

    static String phase(String role) { return role.equals("DIALOGUE") ? "SESSION" : role; }
    static final class InjectedFailure extends RuntimeException { InjectedFailure() { super("CONTROLLED_FAILURE"); } }

    static class ForwardingModel implements ChatModel {
        final ChatModel delegate;
        ForwardingModel(ChatModel delegate) { this.delegate = delegate; }
        @Override public ChatResponse chat(ChatRequest request) { return delegate.chat(request); }
        @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) { return delegate.chat(request, options); }
        @Override public ChatResponse doChat(ChatRequest request) { return chat(request); }
        @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
        @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
        @Override public ModelProvider provider() { return delegate.provider(); }
        @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
    }
}
