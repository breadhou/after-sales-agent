package com.mall.agent.evaluation;

import com.mall.agent.flow.FlowObserver;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Counts and observes one blocking model call while preserving the delegate's SDK behavior. */
public final class MeteredChatModel implements ChatModel {
    private static final Set<String> ROLES = Set.of("DIALOGUE", "REVIEW", "EXPLANATION");

    private final ChatModel delegate;
    private final String role;
    private final TrialBudget budget;
    private final FlowObserver observer;
    private final AtomicLong callSequence = new AtomicLong();

    public MeteredChatModel(ChatModel delegate, String role, TrialBudget budget, FlowObserver observer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Invalid model role");
        this.role = role;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override public ChatResponse chat(ChatRequest request) {
        return invoke(() -> delegate.chat(request));
    }

    @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return invoke(() -> delegate.chat(request, options));
    }

    /** Keeps direct callers of the SDK's low-level blocking hook metered as well. */
    @Override public ChatResponse doChat(ChatRequest request) {
        return chat(request);
    }

    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }

    private ChatResponse invoke(Supplier<ChatResponse> delegateCall) {
        budget.beforeRequest();
        String callId = role.toLowerCase(java.util.Locale.ROOT) + "-" + callSequence.incrementAndGet();
        long started = System.nanoTime();
        final ChatResponse response;
        try {
            response = delegateCall.get();
        } catch (Throwable failure) {
            recordUsage(null);
            observeFailure(callId, elapsed(started), failure);
            return rethrow(failure);
        }

        TokenUsage usage = tokenUsage(response);
        String modelName = modelName(response);
        recordUsage(usage);
        observeResponse(callId, elapsed(started), modelName, usage);
        return response;
    }

    private TokenUsage tokenUsage(ChatResponse response) {
        try { return response == null ? null : response.tokenUsage(); }
        catch (Throwable failure) { observationFailure(failure); return null; }
    }

    private String modelName(ChatResponse response) {
        try { return response == null ? null : response.modelName(); }
        catch (Throwable failure) { observationFailure(failure); return null; }
    }

    private void recordUsage(TokenUsage usage) {
        try { budget.record(usage); }
        catch (Throwable failure) { observationFailure(failure); }
    }

    private void observeResponse(String callId, long duration, String modelName, TokenUsage usage) {
        Map<String, Object> attributes = baseAttributes(callId, "COMPLETED", duration);
        if (modelName != null) attributes.put("modelName", modelName);
        if (usage != null) {
            try {
                putKnownToken(attributes, "promptTokens", usage.inputTokenCount());
                putKnownToken(attributes, "completionTokens", usage.outputTokenCount());
                putKnownToken(attributes, "totalTokens", usage.totalTokenCount());
            } catch (Throwable failure) { observationFailure(failure); }
        }
        FlowObserver.event(observer, "MODEL", null, attributes);
    }

    private void observeFailure(String callId, long duration, Throwable failure) {
        Integer status = null;
        try { status = httpStatus(failure); }
        catch (Throwable diagnosticFailure) { observationFailure(diagnosticFailure); }
        Map<String, Object> attributes = baseAttributes(callId,
                status == null ? "FAILED" : "TRANSPORT_ERROR", duration);
        attributes.put("errorCategory", "MODEL_ERROR");
        String exceptionClass = safeClassName(failure);
        if (exceptionClass != null) attributes.put("exceptionClass", exceptionClass);
        if (status != null) attributes.put("httpStatus", status);
        FlowObserver.event(observer, "MODEL", null, attributes);
    }

    private Map<String, Object> baseAttributes(String callId, String status, long duration) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("role", role);
        attributes.put("status", status);
        attributes.put("callId", callId);
        attributes.put("durationMs", duration);
        return attributes;
    }

    private static void putKnownToken(Map<String, Object> attributes, String field, Integer count) {
        if (count != null && count >= 0) attributes.put(field, count.longValue());
    }

    private static String safeClassName(Throwable failure) {
        String name = failure.getClass().getName();
        return name.length() <= 256 && name.matches("[A-Za-z_$][A-Za-z0-9_$.]*") ? name : null;
    }

    private static Integer httpStatus(Throwable failure) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof HttpException http) {
                int status = http.statusCode();
                return status >= 100 && status <= 599 ? status : null;
            }
        }
        return null;
    }

    private void observationFailure(Throwable failure) {
        try { observer.onObservationFailure(failure); }
        catch (Throwable ignored) { /* Observation cannot replace the provider outcome. */ }
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> ChatResponse rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
