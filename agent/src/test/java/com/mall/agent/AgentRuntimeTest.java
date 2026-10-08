package com.mall.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.flow.FlowObserver;
import com.mall.agent.model.EscalationRecord;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    private static final long ORDER = 9007199254741001L;
    private static final String APPLY = "请退订单 " + ORDER + "，理由：不想要了";
    private static final String CONFIRM = "/confirm-refund " + ORDER;
    private static final String APPROVAL = """
            {"approved":true,"citedPolicyCode":"SEVEN_DAY_NO_REASON","faults":[]}
            """;
    private static final String REJECTION = """
            {"approved":false,"citedPolicyCode":"SEVEN_DAY_NO_REASON",
             "faults":[{"category":"UNCERTAIN","evidence":"需要核实","policyCode":"SEVEN_DAY_NO_REASON"}]}
            """;

    @Test
    void cliAndEvaluationShareConfirmationAndReviewGates() throws Exception {
        for (boolean cli : List.of(true, false)) {
            for (boolean approved : List.of(true, false)) {
                FakeMcp mcp = new FakeMcp();
                DialogueModel dialogue = new DialogueModel();
                ReplyModel review = new ReplyModel(approved ? APPROVAL : REJECTION);
                ChatModel shared = new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        return noTools(request) ? review.doChat(request) : dialogue.doChat(request);
                    }
                };
                AgentRuntime runtime = cli
                        ? AgentMain.createRuntime(mcp.client, shared, Map.of(), "session-a")
                        : AgentRuntime.create(dialogue, review, new ReplyModel("unused"), mcp.client,
                                Set.of(), "session-a", FlowObserver.NOOP, ignored -> { });

                String pending = turn(runtime, cli, APPLY);
                assertTrue(pending.contains(CONFIRM), pending);
                assertTrue(review.requests.isEmpty(), "An unconfirmed candidate must not be reviewed");
                assertTrue(mcp.calls.isEmpty(), "An explicit candidate must not query or submit before confirmation");

                String result = turn(runtime, cli, CONFIRM);
                assertEquals(1, review.requests.size());
                assertEquals(approved ? List.of("get_order", "get_refund_eligibility", "list_policy_clauses", "submit_refund")
                                : List.of("get_order", "get_refund_eligibility", "list_policy_clauses"),
                        mcp.calls.stream().map(ToolExecutionRequest::name).toList());
                if (approved) {
                    assertTrue(result.contains("退款已完成"), result);
                    var actual = new ObjectMapper().readTree(mcp.calls.get(3).arguments());
                    assertEquals(ORDER, actual.path("orderId").longValue());
                    assertEquals("不想要了", actual.path("reason").textValue());
                    assertEquals("fp-runtime", actual.path("expectedCatalogFingerprint").textValue());
                    assertEquals("SEVEN_DAY_NO_REASON", actual.path("expectedPolicyCode").textValue());
                    assertEquals(4, actual.size());
                } else {
                    assertTrue(result.contains("未执行"), result);
                    assertEquals(1, runtime.escalations().records().size());
                    assertEquals("session-a", runtime.escalations().records().get(0).sessionId());
                }
                turn(runtime, cli, CONFIRM);
                assertEquals(1, review.requests.size(), "A consumed confirmation must not run again");
                assertEquals(approved ? 4 : 3, mcp.calls.size());
            }
        }
    }

    @Test
    void newSessionCannotConfirmOldCandidate() {
        FakeMcp mcp = new FakeMcp();
        DialogueModel dialogue = new DialogueModel();
        ReplyModel review = new ReplyModel(APPROVAL);
        AgentRuntime old = create(dialogue, review, mcp, "session-a", FlowObserver.NOOP, ignored -> { });
        assertTrue(old.coordinator().handleTurn("session-a", APPLY + "。old-session-sentinel").contains(CONFIRM));

        AgentRuntime fresh = create(dialogue, review, mcp, "session-b", FlowObserver.NOOP, ignored -> { });
        String result = fresh.coordinator().handleTurn("session-b", CONFIRM);
        assertTrue(result.contains("当前没有待确认"), result);
        assertTrue(review.requests.isEmpty());
        assertTrue(mcp.calls.isEmpty());
        fresh.coordinator().handleTurn("session-b", "新的普通话题");
        assertFalse(dialogue.requests.get(dialogue.requests.size() - 1).messages().toString()
                .contains("old-session-sentinel"), "Every runtime must own fresh dialogue memory");
        assertTrue(old.coordinator().handleTurn("session-a", CONFIRM).contains("退款已完成"));
    }

    @Test
    void threeRolesKeepTheirOriginalToolSets() {
        FakeMcp mcp = new FakeMcp();
        DialogueModel dialogue = new DialogueModel();
        ReplyModel review = new ReplyModel(APPROVAL);
        ReplyModel explanation = new ReplyModel("""
                {"narrative":"请以所引资料原文为准。","citedSourceIds":["FAQ-008"]}
                """);
        RecordingObserver observer = new RecordingObserver();
        AgentRuntime runtime = AgentRuntime.create(dialogue, review, explanation, mcp.client,
                Set.of(), "session-a", observer, ignored -> { });
        runtime.coordinator().handleTurn("session-a", APPLY);
        runtime.coordinator().handleTurn("session-a", CONFIRM);
        String answer = runtime.coordinator().handleTurn("session-a", "物流查询失败怎么办？");

        assertTrue(answer.contains("[FAQ-008]"), answer);
        Set<String> visible = dialogue.requests.get(0).toolSpecifications().stream()
                .map(ToolSpecification::name).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("get_order", "list_user_orders", "get_logistics", "handoff_refund",
                "ask_refund_eligibility", "escalate_to_human", "request_explanation"), visible);
        assertEquals(1, review.requests.size());
        assertEquals(1, explanation.requests.size());
        assertTrue(review.requests.stream().allMatch(AgentRuntimeTest::noTools));
        assertTrue(explanation.requests.stream().allMatch(AgentRuntimeTest::noTools));
        assertTrue(observer.events.stream().map(Seen::phase).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of("SESSION", "CONFIRMATION", "FACTS", "POLICY", "REVIEW", "EXECUTION", "EXPLANATION")));
        assertTrue(observer.events.stream().anyMatch(event -> event.phase.equals("CONFIRMATION")
                && Long.valueOf(ORDER).equals(event.target) && event.attributes.get("status").equals("COMPLETED")));
        assertTrue(observer.events.stream().anyMatch(event -> event.phase.equals("EXPLANATION")
                && "SOURCE_ORIGINAL".equals(event.attributes.get("replyKind"))));
        assertTrue(observer.sources.contains("SEVEN_DAY_NO_REASON"));
        assertTrue(observer.sources.contains("FAQ-008"));
    }

    @Test
    void sameObserverCapturesReviewParsingFailureAndEscalationSink() {
        FakeMcp mcp = new FakeMcp();
        RecordingObserver observer = new RecordingObserver();
        List<EscalationRecord> delivered = new ArrayList<>();
        AgentRuntime runtime = create(new DialogueModel(), new ReplyModel("not-json"), mcp,
                "session-a", observer, delivered::add);
        runtime.coordinator().handleTurn("session-a", APPLY);
        String result = runtime.coordinator().handleTurn("session-a", CONFIRM);

        assertTrue(result.contains("未执行"), result);
        assertFalse(mcp.calls.stream().anyMatch(call -> call.name().equals("submit_refund")));
        assertTrue(observer.events.stream().anyMatch(event -> event.phase.equals("REVIEW")
                && Long.valueOf(ORDER).equals(event.target)
                && "REVIEW_FORMAT_ERROR".equals(event.attributes.get("errorCategory"))
                && "dev.langchain4j.service.output.OutputParsingException".equals(event.attributes.get("exceptionClass"))));
        assertTrue(observer.events.stream().anyMatch(event -> event.phase.equals("ESCALATION")));
        assertEquals(1, delivered.size());
        assertSame(runtime.escalations().records().get(0), delivered.get(0));
        assertEquals("session-a", delivered.get(0).sessionId());
        assertEquals(ORDER, delivered.get(0).orderId());
    }

    @Test
    void callerOwnsMcpOnSuccessfulAndFailedRuntimeConstruction() throws Exception {
        FakeMcp success = new FakeMcp();
        AgentRuntime runtime = create(new DialogueModel(), new ReplyModel(APPROVAL), success,
                "session-a", FlowObserver.NOOP, ignored -> { });
        runtime.coordinator().handleTurn("session-a", "普通话题");
        assertEquals(0, success.closed.get());
        success.client.close();
        assertEquals(1, success.closed.get());

        FakeMcp failure = new FakeMcp();
        failure.advertised = List.of(tool("get_order"), tool("submit_refund"));
        assertThrows(IllegalStateException.class, () -> create(new DialogueModel(), new ReplyModel(APPROVAL),
                failure, "session-b", FlowObserver.NOOP, ignored -> { }));
        assertEquals(0, failure.closed.get(), "Construction failure must leave ownership with its caller");
        failure.client.close();
        assertEquals(1, failure.closed.get());
    }

    private static AgentRuntime create(ChatModel dialogue, ChatModel review, FakeMcp mcp,
                                       String sessionId, FlowObserver observer, Consumer<EscalationRecord> sink) {
        return AgentRuntime.create(dialogue, review, new ReplyModel("unused"), mcp.client,
                Set.of(), sessionId, observer, sink);
    }

    @Test
    void readonlyEligibilityRejectionHasTrustedSameTargetMarker() {
        FakeMcp mcp = new FakeMcp();
        mcp.eligibilityText = "{\"orderId\":" + ORDER + ",\"eligible\":false,\"refundExists\":false,\"reason\":\"PAID不可退\"}";
        RecordingObserver observer = new RecordingObserver();
        ReplyModel review = new ReplyModel(APPROVAL);
        AgentRuntime runtime = create(new DialogueModel(), review, mcp, "session-a", observer, ignored -> { });
        String answer = runtime.coordinator().handleTurn("session-a", "订单 " + ORDER + " 现在可以退款吗？只查询资格");
        assertEquals("订单 " + ORDER + " 当前不可退：PAID不可退。本次未提交退款。", answer);
        List<Seen> markers = observer.events.stream().filter(AgentRuntimeTest::readonlyMarker).toList();
        assertEquals(1, markers.size());
        assertEquals(ORDER, markers.get(0).target);
        assertEquals(Map.of("status", "REJECTED", "tool", "get_refund_eligibility"), markers.get(0).attributes);
        assertTrue(observer.events.indexOf(markers.get(0)) < observer.events.size() - 1);
        assertEquals(List.of("get_refund_eligibility"), mcp.calls.stream().map(ToolExecutionRequest::name).toList());
        assertTrue(review.requests.isEmpty());
        assertFalse(observer.events.stream().anyMatch(seen -> seen.phase.equals("FACTS")));
    }

    @Test
    void readonlyEligibilityRejectsUntrustedObjectsWithoutMarker() {
        for (String body : List.of("not-json", "{\"orderId\":" + (ORDER + 1) + ",\"eligible\":false,\"refundExists\":false,\"reason\":\"拒绝\"}",
                "{\"orderId\":" + ORDER + ",\"eligible\":\"false\",\"refundExists\":false,\"reason\":\"拒绝\"}",
                "{\"orderId\":" + ORDER + ",\"eligible\":false,\"refundExists\":\"false\",\"reason\":\"拒绝\"}",
                "{\"orderId\":" + ORDER + ",\"eligible\":true,\"refundExists\":false,\"reason\":\"拒绝\"}",
                "{\"orderId\":" + ORDER + ",\"eligible\":false,\"refundExists\":false,\"reason\":\"  \"}",
                "{\"orderId\":" + ORDER + ",\"eligible\":false,\"refundExists\":false,\"reason\":7}",
                "{\"error\":true,\"code\":403,\"message\":\"拒绝\"}")) {
            FakeMcp mcp = new FakeMcp(); mcp.eligibilityText = body;
            RecordingObserver observer = new RecordingObserver();
            AgentRuntime runtime = create(new DialogueModel(), new ReplyModel(APPROVAL), mcp, "session-a", observer, ignored -> { });
            runtime.coordinator().handleTurn("session-a", "订单 " + ORDER + " 现在可以退款吗？只查询资格");
            assertTrue(observer.events.stream().noneMatch(AgentRuntimeTest::readonlyMarker), body);
        }
        FakeMcp failed = new FakeMcp(); failed.eligibilityFailure = true;
        RecordingObserver observer = new RecordingObserver();
        AgentRuntime runtime = create(new DialogueModel(), new ReplyModel(APPROVAL), failed, "session-a", observer, ignored -> { });
        assertTrue(runtime.coordinator().handleTurn("session-a", "订单 " + ORDER + " 现在可以退款吗？只查询资格").contains("无法确认"));
        assertTrue(observer.events.stream().noneMatch(AgentRuntimeTest::readonlyMarker));
    }

    @Test
    void readonlyEligibilityObservationFailureDoesNotChangeReply() {
        FakeMcp mcp = new FakeMcp();
        mcp.eligibilityText = "{\"orderId\":" + ORDER + ",\"eligible\":false,\"refundExists\":false,\"reason\":\"PAID不可退\"}";
        AtomicInteger failures = new AtomicInteger();
        FlowObserver observer = new FlowObserver() {
            public void onEvent(String phase, Long target, Map<String, Object> attributes) {
                if (readonlyMarker(new Seen(phase, target, attributes))) throw new IllegalStateException("fake-observer-sentinel");
            }
            public void onSourceEvidence(String key, String text, String digest) { }
            public void onObservationFailure(Throwable failure) { failures.incrementAndGet(); }
        };
        AgentRuntime runtime = create(new DialogueModel(), new ReplyModel(APPROVAL), mcp, "session-a", observer, ignored -> { });
        assertEquals("订单 " + ORDER + " 当前不可退：PAID不可退。本次未提交退款。",
                runtime.coordinator().handleTurn("session-a", "订单 " + ORDER + " 现在可以退款吗？只查询资格"));
        assertEquals(1, failures.get());
    }

    private static boolean readonlyMarker(Seen seen) {
        return seen.phase.equals("SESSION") && "REJECTED".equals(seen.attributes.get("status"))
                && "get_refund_eligibility".equals(seen.attributes.get("tool"));
    }

    private static String turn(AgentRuntime runtime, boolean cli, String input) throws Exception {
        if (!cli) return runtime.coordinator().handleTurn("session-a", input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AgentMain.runSession(runtime.coordinator(), "session-a", new BufferedReader(new StringReader(input + "\nexit\n")),
                new PrintStream(output, true, StandardCharsets.UTF_8));
        return output.toString(StandardCharsets.UTF_8);
    }

    private static boolean noTools(ChatRequest request) {
        return request.toolSpecifications() == null || request.toolSpecifications().isEmpty();
    }

    private static ChatResponse reply(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }

    private static ToolSpecification tool(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private static final class DialogueModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();
        @Override public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            var last = request.messages().get(request.messages().size() - 1);
            if (last instanceof UserMessage && last.toString().contains("请退订单")) {
                return reply(AiMessage.from(ToolExecutionRequest.builder().id("handoff-1")
                        .name("handoff_refund").arguments("{\"orderId\":" + ORDER + ",\"reason\":\"不想要了\"}").build()));
            }
            if (last instanceof UserMessage && last.toString().contains("物流查询失败")) {
                return reply(AiMessage.from(ToolExecutionRequest.builder().id("explain-1")
                        .name("request_explanation").arguments("{\"orderId\":0}").build()));
            }
            return reply(new AiMessage("请以客服确认信息为准。"));
        }
    }

    private static final class ReplyModel implements ChatModel {
        private final String text;
        private final List<ChatRequest> requests = new ArrayList<>();
        private ReplyModel(String text) { this.text = text; }
        @Override public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return reply(new AiMessage(text));
        }
    }

    private record Seen(String phase, Long target, Map<String, Object> attributes) { }

    private static final class RecordingObserver implements FlowObserver {
        private final List<Seen> events = new ArrayList<>();
        private final List<String> sources = new ArrayList<>();
        @Override public void onEvent(String phase, Long target, Map<String, Object> attributes) {
            events.add(new Seen(phase, target, Map.copyOf(attributes)));
        }
        @Override public void onSourceEvidence(String key, String text, String digest) { sources.add(key); }
    }

    private static final class FakeMcp {
        private String eligibilityText;
        private boolean eligibilityFailure;
        private List<ToolSpecification> advertised = List.of(tool("get_order"), tool("list_user_orders"),
                tool("get_logistics"), tool("get_refund_eligibility"), tool("list_policy_clauses"), tool("submit_refund"));
        private final List<ToolExecutionRequest> calls = new ArrayList<>();
        private final AtomicInteger closed = new AtomicInteger();
        private final McpClient client = (McpClient) Proxy.newProxyInstance(AgentRuntimeTest.class.getClassLoader(),
                new Class<?>[]{McpClient.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "listTools" -> advertised;
                        case "close" -> { closed.incrementAndGet(); yield null; }
                        case "executeTool" -> execute((ToolExecutionRequest) args[0]);
                        default -> throw new AssertionError("Unexpected MCP call: " + method.getName());
                    };
                });
        private ToolExecutionResult execute(ToolExecutionRequest request) {
            calls.add(request);
            if (request.name().equals("get_refund_eligibility")) {
                if (eligibilityFailure) throw new IllegalStateException("fake-mcp-failure");
                if (eligibilityText != null) return ToolExecutionResult.builder().isError(false).resultText(eligibilityText).build();
            }
            String text = switch (request.name()) {
                case "get_order" -> "{\"id\":" + ORDER + ",\"status\":\"RECEIVED\",\"totalAmount\":199.99}";
                case "get_refund_eligibility" -> "{\"orderId\":" + ORDER + ",\"orderStatus\":\"RECEIVED\","
                        + "\"eligible\":true,\"refundExists\":false,\"refundableAmount\":199.990,"
                        + "\"catalogFingerprint\":\"fp-runtime\",\"policyCode\":\"SEVEN_DAY_NO_REASON\",\"policyTitle\":\"七日无理由\"}";
                case "list_policy_clauses" -> "{\"fingerprint\":\"fp-runtime\",\"clauses\":[{\"code\":\"SEVEN_DAY_NO_REASON\","
                        + "\"title\":\"七日无理由\",\"clauseText\":\"七日内可申请退款。\"}]}";
                case "submit_refund" -> "{\"orderId\":" + ORDER + ",\"eligible\":true,\"refundExists\":false,\"refundableAmount\":199.99}";
                default -> throw new AssertionError("Unexpected tool: " + request.name());
            };
            return ToolExecutionResult.builder().isError(false).resultText(text).build();
        }
    }
}
