package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mall.agent.flow.FlowObserver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeteredChatModelTest {

    @Test
    void preservesRequestOptionsToolsAndResponseIdentity() {
        ChatResponse response = response(new TokenUsage(3, 4, 7));
        RecordingModel delegate = new RecordingModel(response);
        TrialBudget budget = new TrialBudget(12, 100);
        RecordingObserver observer = new RecordingObserver();
        MeteredChatModel metered = metered(delegate, "DIALOGUE", budget, observer);
        ToolSpecification tool = ToolSpecification.builder().name("lookup").description("lookup a record").build();
        ChatRequest request = ChatRequest.builder().messages(AiMessage.from("question"))
                .toolSpecifications(tool).build();
        ChatRequestOptions options = ChatRequestOptions.builder().addListenerAttribute("trace", "kept").build();

        ChatResponse actual = metered.chat(request, options);

        assertSame(response, actual);
        assertSame(request, delegate.optionsRequest);
        assertSame(options, delegate.options);
        assertEquals(List.of(tool), delegate.optionsRequest.toolSpecifications());
        assertEquals(0, delegate.singleCalls.get());
        assertEquals(1, delegate.optionsCalls.get());
        assertEquals(1, budget.snapshot().path("logicalModelRequests").asInt());
        assertEquals("DIALOGUE", observer.events.get(0).get("role"));
        assertEquals("COMPLETED", observer.events.get(0).get("status"));
        assertEquals("actual-model", observer.events.get(0).get("modelName"));
        assertTrue((long) observer.events.get(0).get("durationMs") >= 0);
        assertEquals(3L, observer.events.get(0).get("promptTokens"));
        assertEquals(4L, observer.events.get(0).get("completionTokens"));
        assertEquals(7L, observer.events.get(0).get("totalTokens"));
    }

    @Test
    void countsExactlyOneLogicalCallForEachOverload() {
        RecordingModel delegate = new RecordingModel(response(new TokenUsage(2, 3, 5)));
        TrialBudget budget = new TrialBudget(12, 100);
        MeteredChatModel metered = metered(delegate, "REVIEW", budget, new RecordingObserver());
        ChatRequest request = request();

        metered.chat(request);
        metered.chat(request, ChatRequestOptions.EMPTY);

        assertEquals(1, delegate.singleCalls.get());
        assertEquals(1, delegate.optionsCalls.get());
        assertSame(request, delegate.singleRequest);
        assertSame(request, delegate.optionsRequest);
        assertEquals(2, budget.snapshot().path("logicalModelRequests").asInt());
        assertEquals(0, budget.snapshot().path("unknownUsageRequests").asInt());
    }

    @Test
    void delegatesModelMetadataAndDoesNotDuplicateSdkListeners() {
        AtomicInteger requestEvents = new AtomicInteger();
        AtomicInteger responseEvents = new AtomicInteger();
        AtomicInteger errorEvents = new AtomicInteger();
        ChatModelListener listener = new ChatModelListener() {
            @Override public void onRequest(ChatModelRequestContext context) { requestEvents.incrementAndGet(); }
            @Override public void onResponse(ChatModelResponseContext context) { responseEvents.incrementAndGet(); }
            @Override public void onError(ChatModelErrorContext context) { errorEvents.incrementAndGet(); }
        };
        List<ChatModelListener> listeners = List.of(listener);
        ChatRequestParameters parameters = ChatRequestParameters.builder().modelName("configured-model").build();
        Set<Capability> capabilities = Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
        ChatResponse response = response(new TokenUsage(1, 2, 3));
        SdkDefaultModel delegate = new SdkDefaultModel(response, parameters, listeners, capabilities);
        MeteredChatModel metered = metered(delegate, "EXPLANATION", new TrialBudget(12, 100), new RecordingObserver());

        assertSame(parameters, metered.defaultRequestParameters());
        assertSame(listeners, metered.listeners());
        assertSame(ModelProvider.OPEN_AI, metered.provider());
        assertSame(capabilities, metered.supportedCapabilities());
        assertSame(response, metered.chat(request()));
        assertEquals(1, delegate.doChatCalls.get());
        assertEquals(1, requestEvents.get());
        assertEquals(1, responseEvents.get());
        assertEquals(0, errorEvents.get());
    }

    @Test
    void partialUsageRemainsPartialEvenWhenSdkReportsTotal() {
        TrialBudget budget = new TrialBudget(12, 100);
        MeteredChatModel metered = metered(new RecordingModel(response(new TokenUsage(null, 7))),
                "DIALOGUE", budget, new RecordingObserver());

        metered.chat(request());

        JsonNode snapshot = budget.snapshot();
        assertEquals(1, snapshot.path("logicalModelRequests").asInt());
        assertTrue(snapshot.get("promptTokens").isNull());
        assertEquals(7, snapshot.path("completionTokens").asLong());
        assertEquals(7, snapshot.path("totalTokens").asLong());
        assertFalse(snapshot.path("usageComplete").asBoolean());
        assertEquals(1, snapshot.path("unknownUsageRequests").asInt());
        assertEquals(Set.of("logicalModelRequests", "promptTokens", "completionTokens", "totalTokens",
                        "usageComplete", "unknownUsageRequests"), fieldNames(snapshot));
    }

    @Test
    void thirteenthRequestIsRejectedBeforeAnyRoleDelegate() {
        TrialBudget budget = new TrialBudget(20, 1_000);
        RecordingObserver observer = new RecordingObserver();
        RecordingModel dialogue = new RecordingModel(response(new TokenUsage(1, 1, 2)));
        RecordingModel review = new RecordingModel(response(new TokenUsage(1, 1, 2)));
        RecordingModel explanation = new RecordingModel(response(new TokenUsage(1, 1, 2)));
        List<MeteredChatModel> roles = List.of(
                metered(dialogue, "DIALOGUE", budget, observer),
                metered(review, "REVIEW", budget, observer),
                metered(explanation, "EXPLANATION", budget, observer));

        for (int i = 0; i < 12; i++) roles.get(i % roles.size()).chat(request());

        TrialBudget.BudgetExceededException rejected = assertThrows(
                TrialBudget.BudgetExceededException.class, () -> roles.get(0).chat(request()));
        assertEquals("BUDGET_STOP", rejected.errorCategory());
        assertEquals(4, dialogue.singleCalls.get());
        assertEquals(4, review.singleCalls.get());
        assertEquals(4, explanation.singleCalls.get());
        assertEquals(12, budget.snapshot().path("logicalModelRequests").asInt());
        assertEquals(12, observer.events.size());
    }

    @Test
    void honorsSmallerTrialRequestAllowance() {
        RecordingModel delegate = new RecordingModel(response(new TokenUsage(1, 1, 2)));
        TrialBudget budget = new TrialBudget(2, 1_000);
        MeteredChatModel metered = metered(delegate, "REVIEW", budget, new RecordingObserver());

        metered.chat(request());
        metered.chat(request());
        TrialBudget.BudgetExceededException rejected = assertThrows(
                TrialBudget.BudgetExceededException.class, () -> metered.chat(request()));

        assertEquals("BUDGET_STOP", rejected.errorCategory());
        assertEquals(2, delegate.singleCalls.get());
        assertEquals(2, budget.snapshot().path("logicalModelRequests").asInt());
    }

    @Test
    void stopsTheNextCallAfterReportedTokenAllowanceIsReached() {
        TrialBudget budget = new TrialBudget(12, 7);
        RecordingModel delegate = new RecordingModel(response(new TokenUsage(null, 7)));
        MeteredChatModel metered = metered(delegate, "REVIEW", budget, new RecordingObserver());

        metered.chat(request());

        assertThrows(TrialBudget.BudgetExceededException.class, () -> metered.chat(request()));
        assertEquals(1, delegate.singleCalls.get());
        assertEquals(1, budget.snapshot().path("logicalModelRequests").asInt());
    }

    @Test
    void retainsOriginalThrowableAndOnlyRecordsTypedHttpStatus() {
        RuntimeException failure = new RuntimeException("private error text", new HttpException(503, "private body"));
        RecordingModel delegate = new RecordingModel(failure);
        RecordingObserver observer = new RecordingObserver();
        TrialBudget budget = new TrialBudget(12, 100);
        MeteredChatModel metered = metered(delegate, "DIALOGUE", budget, observer);

        RuntimeException actual = assertThrows(RuntimeException.class, () -> metered.chat(request()));

        assertSame(failure, actual);
        assertEquals(1, budget.snapshot().path("logicalModelRequests").asInt());
        assertEquals(1, budget.snapshot().path("unknownUsageRequests").asInt());
        assertEquals(1, observer.events.size());
        Map<String, Object> event = observer.events.get(0);
        assertEquals("MODEL_ERROR", event.get("errorCategory"));
        assertEquals("java.lang.RuntimeException", event.get("exceptionClass"));
        assertEquals(503, event.get("httpStatus"));
        assertFalse(event.containsValue("private error text"));
        assertFalse(event.containsValue("private body"));
    }

    @Test
    void observerFailureCannotReplaceOriginalProviderThrowable() {
        RuntimeException failure = new RuntimeException("private error text");
        RecordingModel delegate = new RecordingModel(failure);
        AtomicInteger failures = new AtomicInteger();
        FlowObserver observer = new FlowObserver() {
            @Override public void onEvent(String phase, Long targetId, Map<String, Object> attributes) {
                throw new IllegalStateException("observer failure");
            }
            @Override public void onSourceEvidence(String sourceKey, String text, String digest) { }
            @Override public void onObservationFailure(Throwable observedFailure) { failures.incrementAndGet(); }
        };
        TrialBudget budget = new TrialBudget(12, 100);
        MeteredChatModel metered = metered(delegate, "DIALOGUE", budget, observer);

        RuntimeException actual = assertThrows(RuntimeException.class, () -> metered.chat(request()));

        assertSame(failure, actual);
        assertEquals(1, failures.get());
        assertEquals(1, budget.snapshot().path("logicalModelRequests").asInt());
        assertEquals(1, budget.snapshot().path("unknownUsageRequests").asInt());
    }

    @Test
    void observerFailureCannotReplaceSuccessfulModelResponse() {
        ChatResponse response = response(new TokenUsage(1, 2, 3));
        RecordingModel delegate = new RecordingModel(response);
        AtomicInteger failures = new AtomicInteger();
        FlowObserver observer = new FlowObserver() {
            @Override public void onEvent(String phase, Long targetId, Map<String, Object> attributes) {
                throw new IllegalStateException("observer failure");
            }
            @Override public void onSourceEvidence(String sourceKey, String text, String digest) { }
            @Override public void onObservationFailure(Throwable failure) { failures.incrementAndGet(); }
        };
        TrialBudget budget = new TrialBudget(12, 100);
        MeteredChatModel metered = metered(delegate, "EXPLANATION", budget, observer);

        assertSame(response, metered.chat(request()));
        assertEquals(1, failures.get());
        assertEquals(1, budget.snapshot().path("logicalModelRequests").asInt());
        assertTrue(budget.snapshot().path("usageComplete").asBoolean());
    }

    private static MeteredChatModel metered(ChatModel delegate, String role, TrialBudget budget, FlowObserver observer) {
        return new MeteredChatModel(delegate, role, budget, observer);
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(AiMessage.from("test request")).build();
    }

    private static ChatResponse response(TokenUsage usage) {
        return ChatResponse.builder().aiMessage(AiMessage.from("reply")).modelName("actual-model")
                .tokenUsage(usage).build();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static final class RecordingModel implements ChatModel {
        private final ChatResponse response;
        private final Throwable failure;
        private final AtomicInteger singleCalls = new AtomicInteger();
        private final AtomicInteger optionsCalls = new AtomicInteger();
        private ChatRequest singleRequest;
        private ChatRequest optionsRequest;
        private ChatRequestOptions options;

        private RecordingModel(ChatResponse response) { this.response = response; this.failure = null; }
        private RecordingModel(Throwable failure) { this.response = null; this.failure = failure; }

        @Override public ChatResponse chat(ChatRequest request) {
            singleCalls.incrementAndGet();
            singleRequest = request;
            return result();
        }

        @Override public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
            optionsCalls.incrementAndGet();
            optionsRequest = request;
            this.options = options;
            return result();
        }

        private ChatResponse result() {
            if (failure != null) return RecordingModel.<RuntimeException, ChatResponse>throwUnchecked(failure);
            return response;
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable, R> R throwUnchecked(Throwable failure) throws T { throw (T) failure; }
    }

    private static final class SdkDefaultModel implements ChatModel {
        private final ChatResponse response;
        private final ChatRequestParameters parameters;
        private final List<ChatModelListener> listeners;
        private final Set<Capability> capabilities;
        private final AtomicInteger doChatCalls = new AtomicInteger();

        private SdkDefaultModel(ChatResponse response, ChatRequestParameters parameters,
                                List<ChatModelListener> listeners, Set<Capability> capabilities) {
            this.response = response; this.parameters = parameters; this.listeners = listeners; this.capabilities = capabilities;
        }

        @Override public ChatResponse doChat(ChatRequest request) { doChatCalls.incrementAndGet(); return response; }
        @Override public ChatRequestParameters defaultRequestParameters() { return parameters; }
        @Override public List<ChatModelListener> listeners() { return listeners; }
        @Override public ModelProvider provider() { return ModelProvider.OPEN_AI; }
        @Override public Set<Capability> supportedCapabilities() { return capabilities; }
    }

    private static final class RecordingObserver implements FlowObserver {
        private final List<Map<String, Object>> events = new ArrayList<>();

        @Override public void onEvent(String phase, Long targetId, Map<String, Object> attributes) {
            assertEquals("MODEL", phase);
            events.add(new HashMap<>(attributes));
        }
        @Override public void onSourceEvidence(String sourceKey, String text, String digest) { }
    }
}
