package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import com.mall.agent.flow.FlowObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TrialExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ORDER = 9007199254741001L;
    private static final String APPROVED = "{\"approved\":true,\"citedPolicyCode\":\"SEVEN_DAY_NO_REASON\",\"faults\":[]}";
    @TempDir Path directory;

    @Test void liveModeCannotInstallSubstitutions() throws Exception {
        ObjectNode c = live();
        c.set("control", control("MODEL_SCRIPT"));
        assertThrows(IllegalArgumentException.class, () -> CaseSpec.parse(c));
        FakeMcp mcp = new FakeMcp();
        JsonNode result = execute(live(), mcp, new ReplyModel(APPROVED), 12);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertEquals(1, mcp.submits());
        JsonNode actualArgs = JSON.readTree(mcp.calls.get(mcp.calls.size()-1).arguments());
        assertEquals(Set.of("orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"), fields(actualArgs));
        assertEquals(ORDER, actualArgs.path("orderId").longValue(), "orderNo differs from the supported production target ID");
        assertFalse(events(result).stream().anyMatch(e -> e.path("status").asText().equals("SCRIPTED")));
    }

    @Test void reviewOnlyNeverCreatesClientOrExecutor() throws Exception {
        AtomicInteger clients = new AtomicInteger();
        JsonNode result = new TrialExecutor(alias -> { clients.incrementAndGet(); throw new AssertionError(); },
                new ReplyModel(APPROVED)).execute(CaseSpec.parse(review()), reviewBindings(), directory, 12, 10000);
        assertEquals(0, clients.get());
        assertEquals("NOT_SENT", result.path("terminalEvidence").asText());
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertTrue(events(result).stream().noneMatch(e -> Set.of("MCP", "EXECUTION").contains(e.path("phase").asText())));
        assertTrue(privateRecords(result).stream().anyMatch(e -> e.path("outcome").asText().equals("APPROVED")));
    }

    @Test void lostReplyAfterCommitDoesNotRetry() throws Exception {
        FakeMcp mcp = new FakeMcp();
        ObjectNode c = controlled("MCP_AFTER_RESPONSE");
        ((ObjectNode)c.path("control")).put("toolName", "submit_refund").put("response", "lost-private-sentinel");
        addTurn(c, "session-a", "请退订单 {{order-a}}，理由：不想要了");
        addTurn(c, "session-a", "/confirm-refund {{order-a}}");
        JsonNode result = execute(c, mcp, new ReplyModel(APPROVED), 12);
        assertEquals(1, mcp.submits());
        assertEquals("COMPLETED", result.path("terminalEvidence").asText());
        assertEquals("UNRESOLVED_WRITE", result.path("errorCategory").asText());
        assertTrue(privateRecords(result).stream().anyMatch(e -> e.path("kind").asText().equals("ERROR")
                && e.path("errorCategory").asText().equals("UNRESOLVED_WRITE") && e.path("sessionAlias").asText().equals("session-a") && e.path("turnIndex").asInt()==1));
        assertTrue(Files.readString(directory.resolve("reply-session-a-1.txt")).contains("无法确认"));
        assertFalse(Files.readString(directory.resolve(result.path("eventsFile").asText())).contains("lost-private-sentinel"));
    }

    @Test void realUnresolvedWriteNeverPretendsCompletedOrContinuesTurns() throws Exception {
        FakeMcp mcp = new FakeMcp();
        mcp.timeoutOnSubmit = true;
        ObjectNode c = live();
        addTurn(c, "session-a", "请退订单 {{order-a}}，理由：不想要了");
        addTurn(c, "session-a", "/confirm-refund {{order-a}}");
        JsonNode result = execute(c, mcp, new ReplyModel(APPROVED), 12);
        assertEquals(1, mcp.submits());
        assertEquals("UNKNOWN", result.path("terminalEvidence").asText());
        assertEquals("UNRESOLVED_WRITE", result.path("errorCategory").asText());
        assertFalse(Files.exists(directory.resolve("reply-session-a-2.txt")));
    }

    @Test void newSessionUsesFreshMemory() throws Exception {
        ObjectNode c = live();
        c.set("turns", JSON.createArrayNode());
        addTurn(c, "session-a", "普通话题 old-session-private-sentinel");
        addTurn(c, "session-a", "请退订单 {{order-a}}，理由：不想要了");
        addTurn(c, "session-b", "/confirm-refund {{order-a}}");
        addTurn(c, "session-b", "新的普通话题");
        ReplyModel model = new ReplyModel("普通回复");
        FakeMcp mcp = new FakeMcp();
        JsonNode result = execute(c, mcp, model, 12);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertEquals(0, mcp.submits());
        assertFalse(model.requests.get(model.requests.size()-1).messages().toString().contains("old-session-private-sentinel"));
        assertTrue(Files.readString(directory.resolve("reply-session-b-2.txt")).contains("当前没有待确认"));
        assertEquals(2, mcp.closed.get(), "One caller-owned client per fresh session must be closed");
    }

    @Test void reviewParseErrorIsUnavailableNotInterception() throws Exception {
        ReplyModel model = new ReplyModel("not-json-private-sentinel");
        JsonNode result = new TrialExecutor(alias -> { throw new AssertionError(); }, model)
                .execute(CaseSpec.parse(review()), reviewBindings(), directory, 12, 10000);
        assertEquals("REVIEW_FORMAT_ERROR", result.path("errorCategory").asText());
        assertEquals(1, result.path("metering").path("logicalModelRequests").asInt());
        assertTrue(privateRecords(result).stream().anyMatch(e -> e.path("outcome").asText().equals("ERROR")));
        assertFalse(privateRecords(result).stream().anyMatch(e -> e.path("outcome").asText().equals("REJECTED")));
        String publicText = Files.readString(directory.resolve(result.path("eventsFile").asText()));
        assertTrue(publicText.contains("OutputParsingException"));
        assertFalse(publicText.contains("not-json-private-sentinel"));
    }

    @Test void budgetRejectionInsideSafeReviewRemainsBudgetStopWithoutFakeProviderCall() throws Exception {
        ReplyModel model = new ReplyModel(APPROVED);
        JsonNode result = new TrialExecutor(alias -> { throw new AssertionError(); }, model)
                .execute(CaseSpec.parse(review()), reviewBindings(), directory, 0, 10000);
        assertEquals("BUDGET_STOP", result.path("errorCategory").asText());
        assertEquals(0, model.requests.size());
        assertEquals(0, result.path("metering").path("logicalModelRequests").asInt());
        assertTrue(events(result).stream().noneMatch(e -> e.path("phase").asText().equals("MODEL")));
        assertFalse(privateRecords(result).stream().anyMatch(e -> e.path("outcome").asText().equals("REJECTED")));
    }

    @Test void scriptedRoleRecordsScriptedStepsWithoutProviderUsage() throws Exception {
        ObjectNode c = controlled("MODEL_SCRIPT");
        c.set("turns", JSON.createArrayNode());
        addTurn(c, "session-a", "普通资料 private-user-sentinel");
        ObjectNode control = (ObjectNode)c.path("control");
        ((ObjectNode)control.path("components")).put("DIALOGUE", "SUBSTITUTED").put("REVIEW", "ABSENT").put("EXPLANATION", "ABSENT");
        control.put("usesRealModel", false);
        control.set("script", JSON.createObjectNode().put("role", "DIALOGUE").set("responses",
                JSON.createArrayNode().add(JSON.createObjectNode().put("text", "private-scripted-reply"))));
        ReplyModel model = new ReplyModel("never-called");
        JsonNode result = execute(c, new FakeMcp(), model, 0);
        assertEquals(0, model.requests.size());
        assertEquals(0, result.path("metering").path("logicalModelRequests").asInt());
        assertTrue(result.path("metering").path("totalTokens").isNull());
        assertTrue(events(result).stream().anyMatch(e -> e.path("status").asText().equals("SCRIPTED") && e.path("role").asText().equals("DIALOGUE")));
        assertTrue(Files.readString(directory.resolve("reply-session-a-0.txt")).contains("private-scripted-reply"));
        assertFalse(Files.readString(directory.resolve("events.jsonl")).contains("private-user-sentinel"));
    }

    @Test void directContractUsesFixedRealToolArgumentsAndNoModel() throws Exception {
        ObjectNode c = controlled("NONE");
        ObjectNode ctrl = (ObjectNode)c.path("control");
        ctrl.put("target", "MCP_CONTRACT").put("usesRealModel", false);
        ((ObjectNode)ctrl.path("components")).put("DIALOGUE", "ABSENT").put("REVIEW", "ABSENT").put("EXPLANATION", "ABSENT");
        ctrl.set("toolCalls", JSON.createArrayNode().add(JSON.createObjectNode().put("toolName", "get_order")
                .set("arguments", JSON.createObjectNode().put("orderId", "{{order-a}}"))));
        ReplyModel model = new ReplyModel("never-called");
        FakeMcp mcp = new FakeMcp();
        JsonNode result = execute(c, mcp, model, 0);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertEquals(0, model.requests.size());
        assertEquals(List.of("get_order"), mcp.calls.stream().map(ToolExecutionRequest::name).toList());
        assertEquals(ORDER, JSON.readTree(mcp.calls.get(0).arguments()).path("orderId").longValue());
        assertEquals(1, mcp.closed.get());
    }

    @Test void backendProbeIsRefusedBeforeAnyJavaClientOrModel() throws Exception {
        ObjectNode c = controlled("BACKEND_PROBE");
        ObjectNode ctrl = (ObjectNode)c.path("control");
        ctrl.put("target", "BACKEND_TRANSACTION").put("usesRealModel", false).put("probe", "STALE_POLICY");
        ((ObjectNode)ctrl.path("components")).put("DIALOGUE", "ABSENT").put("REVIEW", "ABSENT").put("EXPLANATION", "ABSENT").put("MCP", "ABSENT");
        AtomicInteger clients = new AtomicInteger();
        ReplyModel model = new ReplyModel("never-called");
        JsonNode result = new TrialExecutor(alias -> { clients.incrementAndGet(); throw new AssertionError(); }, model)
                .execute(CaseSpec.parse(c), bindings(), directory, 12, 10000);
        assertEquals("FIXTURE_ERROR", result.path("errorCategory").asText());
        assertEquals(0, clients.get());
        assertEquals(0, model.requests.size());
    }

    @Test void constructionFailureClosesClientAndKeepsSafeError() throws Exception {
        FakeMcp mcp = new FakeMcp();
        mcp.missingTools = true;
        JsonNode result = execute(live(), mcp, new ReplyModel(APPROVED), 12);
        assertEquals("FIXTURE_ERROR", result.path("errorCategory").asText());
        assertEquals(1, mcp.closed.get());
        assertEquals("NOT_SENT", result.path("terminalEvidence").asText());
    }

    @Test void preexistingPrivateSinkIsRejectedBeforeCreatingBusinessComponents() throws Exception {
        Files.writeString(directory.resolve("evidence.json"), "occupied");
        Files.delete(directory.resolve("evidence.json"));
        Files.createDirectory(directory.resolve("evidence.json"));
        FakeMcp mcp = new FakeMcp();
        ReplyModel model = new ReplyModel(APPROVED);
        assertThrows(IllegalArgumentException.class, () -> execute(live(), mcp, model, 12));
        assertEquals(0, mcp.closed.get());
        assertTrue(model.requests.isEmpty());
    }

    @Test void allGeneratedSinksRejectPreexistingOutputBeforePrivateWritesOrBusinessCalls() throws Exception {
        int sinkIndex = 0;
        for (String sink : generatedSinks()) {
            Path output = directory.resolve("occupied-"+sinkIndex++);
            Files.createDirectory(output);
            Path external = directory.resolve("external-"+sinkIndex+".txt");
            Files.writeString(external, "external-sentinel");
            Files.createLink(output.resolve(sink), external);
            AtomicInteger clients = new AtomicInteger();
            ReplyModel model = new ReplyModel(APPROVED);
            CaseSpec spec = CaseSpec.parse(live());
            assertAll(sink,
                    () -> assertThrows(IllegalArgumentException.class, () -> new TrialExecutor(alias -> {
                        clients.incrementAndGet(); return new FakeMcp().client;
                    }, model).execute(spec, bindings(), output, 12, 10000)),
                    () -> assertEquals("external-sentinel", Files.readString(external)),
                    () -> assertEquals(0, clients.get()),
                    () -> assertTrue(model.requests.isEmpty()));
        }
    }

    @Test void mainRejectsEveryPreexistingGeneratedSinkWithoutSuccessfulWire() throws Exception {
        int sinkIndex = 0;
        for (String sink : generatedSinks()) {
            Path runsRoot = directory.resolve("main-occupied-"+sinkIndex++);
            Path config = writeWorkerConfig(runsRoot);
            Path output = config.getParent().resolve("worker");
            Files.createDirectory(output);
            Files.writeString(output.resolve(sink), "owned-output-sentinel");
            AtomicInteger clients = new AtomicInteger();
            ReplyModel model = new ReplyModel(APPROVED);
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            assertAll(sink,
                    () -> assertThrows(IllegalArgumentException.class, () -> EvaluationMain.run(config, runsRoot,
                            alias -> { clients.incrementAndGet(); return new FakeMcp().client; }, model,
                            new PrintStream(wire, true, StandardCharsets.UTF_8))),
                    () -> assertEquals("owned-output-sentinel", Files.readString(output.resolve(sink))),
                    () -> assertEquals(0, clients.get()),
                    () -> assertTrue(model.requests.isEmpty()),
                    () -> assertEquals(0, wire.size()));
        }
    }

    @Test void preexistingSymlinkCannotReplaceExternalPrivateFileWhenCapabilityIsAvailable() throws Exception {
        Path external = directory.resolve("external-link-sentinel.txt");
        Files.writeString(external, "external-sentinel");
        Path probe = directory.resolve("link-capability-probe");
        try { Files.createSymbolicLink(probe, external); }
        catch (UnsupportedOperationException | FileSystemException unavailable) {
            System.out.println("TASK8_SYMLINK_CAPABILITY=UNAVAILABLE:"+unavailable.getClass().getSimpleName());
            // Unconditional plain-file/directory and hard-link ownership regressions above remain required.
            return;
        }
        System.out.println("TASK8_SYMLINK_CAPABILITY=AVAILABLE");
        for (String sink : generatedSinks()) {
            Path root = directory.resolve("symbolic-"+sink.replace('.', '-'));
            Path config = writeWorkerConfig(root);
            Path output = config.getParent().resolve("worker");
            Files.createDirectory(output);
            Files.createSymbolicLink(output.resolve(sink), external);
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            AtomicInteger clients = new AtomicInteger();
            ReplyModel model = new ReplyModel(APPROVED);
            assertAll(sink,
                    () -> assertThrows(IllegalArgumentException.class, () -> EvaluationMain.run(config, root,
                            alias -> { clients.incrementAndGet(); return new FakeMcp().client; }, model,
                            new PrintStream(wire, true, StandardCharsets.UTF_8))),
                    () -> assertEquals("external-sentinel", Files.readString(external)),
                    () -> assertEquals(0, clients.get()),
                    () -> assertTrue(model.requests.isEmpty()),
                    () -> assertEquals(0, wire.size()));
        }
    }

    @Test void declaredScriptTargetsReachActualHandoffAndReadToolsAsExactIds() throws Exception {
        ObjectNode c = scriptedDialogue();
        c.set("turns", JSON.createArrayNode());
        addTurn(c, "session-a", "请退订单 {{order-a}}，理由：不想要了");
        addTurn(c, "session-a", "/confirm-refund {{order-a}}");
        ObjectNode handoffArgs = JSON.createObjectNode().put("orderId", "{{order-a}}").put("reason", "不想要了");
        setScript(c, JSON.createArrayNode()
                .add(JSON.createObjectNode().set("toolCalls", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("name", "get_order").set("arguments", JSON.createObjectNode().put("orderId", "{{order-a}}")))))
                .add(JSON.createObjectNode().set("toolCalls", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("name", "handoff_refund").set("arguments", handoffArgs))))
                .add(JSON.createObjectNode().put("text", "请确认退款申请。")));
        FakeMcp mcp = new FakeMcp();
        ReplyModel provider = new ReplyModel(APPROVED);
        JsonNode result = execute(c, mcp, provider, 12);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertEquals(1, mcp.submits());
        assertTrue(Files.readString(directory.resolve("reply-session-a-0.txt")).contains("/confirm-refund "+ORDER));
        assertTrue(mcp.calls.stream().filter(call -> call.name().equals("get_order") || call.name().equals("submit_refund"))
                .allMatch(call -> { try { return JSON.readTree(call.arguments()).path("orderId").longValue()==ORDER; } catch (Exception e) { return false; } }));
        assertEquals(1, provider.requests.size(), "Only the independent review is a provider call");
        assertEquals(1, result.path("metering").path("logicalModelRequests").asInt());
    }

    @Test void scriptAliasBindingPreservesUnknownToolsAndOtherUntrustedArguments() throws Exception {
        ObjectNode c = scriptedDialogue();
        ObjectNode original = JSON.createObjectNode().put("orderId", "{{order-a}}").put("productId", "{{product-a}}")
                .put("actor", "{{actor-a}}").put("unknown", "{{not-declared}}").put("text", "prefix {{order-a}} suffix")
                .set("nested", JSON.createArrayNode().add(JSON.createObjectNode().put("target", "{{order-a}}")));
        setScript(c, JSON.createArrayNode().add(JSON.createObjectNode().set("toolCalls", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("name", "unregistered_attack_tool").set("arguments", original)))));
        ChatModel provider = new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { throw new AssertionError("Unexpected provider"); } };
        ChatResponse response = ControlledAdapters.model(provider, c.path("control"))
                .chat(ChatRequest.builder().messages(UserMessage.from("script request")).build());
        ToolExecutionRequest tool = response.aiMessage().toolExecutionRequests().get(0);
        assertEquals("unregistered_attack_tool", tool.name());
        assertEquals(original, JSON.readTree(tool.arguments()), "A binding-free adapter still represents arbitrary unknown tools");
        c.set("turns", JSON.createArrayNode()); addTurn(c, "session-a", "请核对订单 {{order-a}}。");
        setScript(c, JSON.createArrayNode().add(JSON.createObjectNode().set("toolCalls", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("name", "get_order").set("arguments", original))))
                .add(JSON.createObjectNode().put("text", "脚本只读查询已结束。")));
        FakeMcp mcp = new FakeMcp();
        JsonNode result = execute(c, mcp, provider, 0);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertEquals(1, mcp.calls.size());
        JsonNode args = JSON.readTree(mcp.calls.get(0).arguments());
        assertTrue(args.path("orderId").isIntegralNumber());
        assertEquals(ORDER, args.path("orderId").longValue());
        assertEquals(9007199254742001L, args.path("productId").longValue());
        assertEquals(ORDER, args.path("nested").get(0).path("target").longValue());
        assertEquals("{{actor-a}}", args.path("actor").textValue());
        assertEquals("{{not-declared}}", args.path("unknown").textValue());
        assertEquals("prefix {{order-a}} suffix", args.path("text").textValue());
        assertEquals("{{order-a}}", original.path("orderId").textValue(), "Caller data stays detached/untrusted");
    }

    @Test void eachDirectMcpStepUsesSamePublicAndPrivateEvidenceIndex() throws Exception {
        ObjectNode c = controlled("NONE");
        ObjectNode ctrl = (ObjectNode)c.path("control");
        ctrl.put("target", "MCP_CONTRACT").put("usesRealModel", false);
        ((ObjectNode)ctrl.path("components")).put("DIALOGUE", "ABSENT").put("REVIEW", "ABSENT").put("EXPLANATION", "ABSENT");
        ctrl.set("toolCalls", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("toolName", "get_order").set("arguments", JSON.createObjectNode().put("orderId", "{{order-a}}")))
                .add(JSON.createObjectNode().put("toolName", "get_product_detail").set("arguments", JSON.createObjectNode().put("productId", "{{product-a}}"))));
        FakeMcp mcp = new FakeMcp();
        JsonNode result = execute(c, mcp, new ReplyModel("never-called"), 0);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        List<JsonNode> called = events(result).stream().filter(e -> e.path("status").asText().equals("CALLED")).toList();
        assertEquals(2, called.size());
        assertAll(() -> assertEquals("order-a", called.get(0).path("target").textValue()),
                () -> assertEquals(0, called.get(0).path("turnIndex").asInt()),
                () -> assertEquals("product-a", called.get(1).path("target").textValue()),
                () -> assertEquals(1, called.get(1).path("turnIndex").asInt()));
        List<JsonNode> finals = privateRecords(result).stream().filter(e -> e.path("kind").asText().equals("FINAL_REPLY")).toList();
        assertEquals(2, finals.size());
        for (int step=0; step<2; step++) {
            int current = step;
            assertEquals(step, finals.get(step).path("turnIndex").asInt());
            assertEquals("mcp-contract", finals.get(step).path("sessionAlias").textValue());
            assertTrue(events(result).stream().anyMatch(e -> e.path("sessionAlias").asText().equals("mcp-contract")
                    && e.path("turnIndex").asInt()==current && e.path("status").asText().equals("RESPONSE_RECEIVED")));
        }
        assertEquals(1, mcp.closed.get());
    }

    @Test void f3DirectSdkDenialIsAnObservedErrorReplyWithoutModelOrWrite() throws Exception {
        ObjectNode c = controlled("NONE");
        ObjectNode ctrl = (ObjectNode)c.path("control");
        ctrl.put("target", "MCP_CONTRACT").put("usesRealModel", false);
        ((ObjectNode)ctrl.path("components")).put("DIALOGUE", "ABSENT").put("REVIEW", "ABSENT").put("EXPLANATION", "ABSENT");
        ctrl.set("toolCalls", JSON.createArrayNode().add(JSON.createObjectNode().put("toolName", "get_order")
                .set("arguments", JSON.createObjectNode().put("orderId", "{{order-a}}"))));
        FakeMcp mcp = new FakeMcp();
        String body = "{\"error\":true,\"code\":50000,\"message\":\"denied\"}";
        mcp.getOrderError = new ToolExecutionException(body);
        JsonNode result = execute(c, mcp, new ReplyModel("never-called"), 0);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertEquals("NOT_SENT", result.path("terminalEvidence").asText());
        assertEquals(0, result.path("metering").path("logicalModelRequests").asInt());
        List<JsonNode> records = privateRecords(result);
        JsonNode source = records.stream().filter(r -> r.path("kind").asText().equals("SOURCE")).findFirst().orElseThrow();
        JsonNode last = records.stream().filter(r -> r.path("kind").asText().equals("FINAL_REPLY")).findFirst().orElseThrow();
        assertEquals(body, source.path("text").asText());
        assertEquals(body, Files.readString(directory.resolve(last.path("file").asText())));
        assertEquals(0, mcp.submits());
        assertEquals(1, mcp.closed.get());
    }

    @Test void workerProtocolUsesConfinedPathsAndOnlySafeStdout() throws Exception {
        Path trial = directory.resolve("run-test/trial-test");
        Files.createDirectories(trial);
        Files.writeString(trial.resolve("case.json"), live().toString());
        Files.writeString(trial.resolve("bindings.json"), bindings().toString());
        Files.writeString(trial.resolve("products.json"), "{\"product-a\":9007199254742001}");
        ObjectNode config = JSON.createObjectNode().put("schemaVersion", 1).put("runId", "run-test")
                .put("caseId", "NORMAL-001").put("trialId", "trial-test").put("caseFile", "case.json")
                .put("bindingFile", "bindings.json").put("productManifest", "products.json").put("workDir", "worker")
                .put("requestAllowance", 12).put("reportedTokenAllowance", 10000);
        Path file = trial.resolve("worker-config.json");
        Files.writeString(file, config.toString());
        ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
        EvaluationMain.run(file, directory, alias -> new FakeMcp().client, new ReplyModel(APPROVED),
                new PrintStream(publicBytes, true, StandardCharsets.UTF_8));
        JsonNode wire = JSON.readTree(publicBytes.toString(StandardCharsets.UTF_8));
        assertEquals(Set.of("schemaVersion", "runId", "caseId", "trialId", "eventsFile", "privateEvidenceFile", "metering", "terminalEvidence", "errorCategory"), fields(wire));
        assertEquals(Set.of("logicalModelRequests", "promptTokens", "completionTokens", "totalTokens", "usageComplete", "unknownUsageRequests"), fields(wire.path("metering")));
        assertFalse(publicBytes.toString(StandardCharsets.UTF_8).contains(Long.toString(ORDER)));
        assertTrue(Files.isRegularFile(trial.resolve("worker").resolve(wire.path("privateEvidenceFile").asText())));
        config.put("caseFile", "../case.json");
        Files.writeString(file, config.toString());
        assertThrows(IllegalArgumentException.class, () -> EvaluationMain.run(file, directory,
                alias -> { throw new AssertionError(); }, new ReplyModel(APPROVED), System.out));
        config.put("caseFile", "case.json").put("extra", "private-value");
        Files.writeString(file, config.toString());
        assertThrows(IllegalArgumentException.class, () -> EvaluationMain.run(file, directory,
                alias -> { throw new AssertionError(); }, new ReplyModel(APPROVED), System.out));
    }

    @Test void bindingsRejectUnknownNestedFieldsBeforeCreatingAClient() throws Exception {
        ObjectNode b = bindings();
        ((ObjectNode)b.path("orders").path("order-a")).put("unapproved", "private-value");
        AtomicInteger clients = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new TrialExecutor(alias -> {
            clients.incrementAndGet(); throw new AssertionError();
        }, new ReplyModel(APPROVED)).execute(CaseSpec.parse(live()), b, directory, 12, 10000));
        assertEquals(0, clients.get());
    }

    @Test void asyncInjectedLossKeepsOriginalTurnAndDrainsItsObservation() throws Exception {
        PrivateEvidenceStore store = new PrivateEvidenceStore(directory);
        SafeEventRecorder recorder = new SafeEventRecorder("run-test", "NORMAL-001", "trial-test", BindingIndex.fromPrivateJson(bindings()), store);
        CompletableFuture<ToolExecutionResult> returned = new CompletableFuture<>();
        McpClient raw = (McpClient)Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (p, m, argv) -> returned);
        ObjectNode control = control("MCP_AFTER_RESPONSE").put("toolName", "get_order").put("response", "private-lost-body");
        CompletableFuture<ToolExecutionResult> lost;
        try (AutoCloseable ignored = recorder.beginTurn("session-a", 3)) {
            lost = ControlledAdapters.mcp(ObservedMcpClient.wrap(raw, BindingIndex.fromPrivateJson(bindings()), recorder), control, recorder)
                    .executeToolAsync(ToolExecutionRequest.builder().name("get_order").arguments("{\"orderId\":"+ORDER+"}").build(), null);
        }
        Thread callback = new Thread(() -> returned.complete(ToolExecutionResult.builder().isError(false).resultText("{}").build()));
        callback.start(); callback.join();
        assertThrows(java.util.concurrent.CompletionException.class, lost::join);
        assertTrue(recorder.awaitObservations(Duration.ofSeconds(1)));
        assertTrue(recorder.evidenceComplete());
        assertTrue(recorder.snapshot().stream().anyMatch(e -> e.path("status").asText().equals("SCRIPTED")
                && e.path("sessionAlias").asText().equals("session-a") && e.path("turnIndex").asInt()==3));
    }

    @Test void finalSourceSubstitutionOccursOnlyAtTheProductionCitationReread() throws Exception {
        ObjectNode c = controlled("SOURCE_BEFORE_FINAL");
        ((ObjectNode)c.path("control")).put("response", "invalid-final-private-source");
        c.set("turns", JSON.createArrayNode()); addTurn(c, "session-a", "商品 {{product-a}} 有什么规格？");
        FakeMcp mcp = new FakeMcp();
        JsonNode result = execute(c, mcp, new ReplyModel("{\"narrative\":\"请以所引资料原文为准。\",\"citedSourceIds\":[\"PRODUCT-9007199254742001\"]}"), 12);
        assertTrue(result.path("errorCategory").isNull(), result.toString());
        assertEquals(1, mcp.calls.stream().filter(call -> call.name().equals("get_product_detail")).count());
        assertTrue(events(result).stream().anyMatch(e -> e.path("status").asText().equals("SCRIPTED")
                && e.path("tool").asText().equals("get_product_detail") && e.path("target").asText().equals("product-a")));
        assertTrue(Files.readString(directory.resolve("reply-session-a-0.txt")).contains("当前无可靠依据"));
        assertTrue(privateRecords(result).stream().noneMatch(e -> e.path("kind").asText().equals("SOURCE") && e.path("sourceKey").asText().startsWith("PRODUCT:")));
    }

    private JsonNode execute(ObjectNode c, FakeMcp mcp, ChatModel model, int allowance) throws Exception {
        return new TrialExecutor(alias -> { assertEquals("actor-a", alias); return mcp.client; }, model)
                .execute(CaseSpec.parse(c), bindings(), directory, allowance, 10000);
    }
    private List<JsonNode> events(JsonNode result) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve(result.path("eventsFile").asText()))) if (!line.isBlank()) out.add(JSON.readTree(line));
        return out;
    }
    private List<JsonNode> privateRecords(JsonNode result) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        JSON.readTree(Files.readString(directory.resolve(result.path("privateEvidenceFile").asText()))).path("records").forEach(out::add);
        return out;
    }
    private static Set<String> fields(JsonNode node) { Set<String> out = new java.util.HashSet<>(); node.fieldNames().forEachRemaining(out::add); return out; }
    private static ObjectNode live() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (root.getFileName().toString().equals("agent")) root = root.getParent();
        ObjectNode c = (ObjectNode)JSON.readTree(Files.readString(root.resolve("eval/fixtures/case-contract.json"))).path("templates").path("live").deepCopy();
        c.set("turns", JSON.createArrayNode());
        addTurn(c, "session-a", "请退订单 {{order-a}}，理由：不想要了");
        addTurn(c, "session-a", "/confirm-refund {{order-a}}");
        return c;
    }
    private static void addTurn(ObjectNode c, String session, String text) {
        ((ArrayNode)c.path("turns")).add(JSON.createObjectNode().put("sessionAlias", session).put("actorAlias", "actor-a").put("input", text));
    }
    private static ObjectNode controlled(String point) throws Exception { ObjectNode c = live(); c.put("mode", "CONTROLLED"); c.set("control", control(point)); return c; }
    private static ObjectNode scriptedDialogue() throws Exception {
        ObjectNode c = controlled("MODEL_SCRIPT");
        ((ObjectNode)c.path("control").path("components")).put("DIALOGUE", "SUBSTITUTED");
        return c;
    }
    private static void setScript(ObjectNode c, ArrayNode responses) { ((ObjectNode)c.path("control")).set("script", JSON.createObjectNode().put("role", "DIALOGUE").set("responses", responses)); }
    private static List<String> generatedSinks() { return List.of("worker-private.log", "case-input.json", "binding-input.json", "events.jsonl", "evidence.json", "reply-session-a-0.txt"); }
    private static Path writeWorkerConfig(Path root) throws Exception {
        Path trial = root.resolve("run-test/trial-test"); Files.createDirectories(trial);
        Files.writeString(trial.resolve("case.json"), live().toString());
        Files.writeString(trial.resolve("bindings.json"), bindings().toString());
        Files.writeString(trial.resolve("products.json"), "{\"product-a\":9007199254742001}");
        Path config = trial.resolve("config.json");
        Files.writeString(config, JSON.createObjectNode().put("schemaVersion", 1).put("runId", "run-test").put("caseId", "NORMAL-001")
                .put("trialId", "trial-test").put("caseFile", "case.json").put("bindingFile", "bindings.json").put("productManifest", "products.json")
                .put("workDir", "worker").put("requestAllowance", 12).put("reportedTokenAllowance", 10000).toString());
        return config;
    }
    private static ObjectNode control(String point) throws Exception {
        return (ObjectNode)JSON.readTree("{\"target\":\"AGENT_CHAIN\",\"components\":{\"DIALOGUE\":\"REAL\",\"REVIEW\":\"REAL\",\"EXPLANATION\":\"REAL\",\"MCP\":\"REAL\",\"BACKEND\":\"REAL\"},\"usesRealModel\":true,\"point\":\""+point+"\"}");
    }
    private static ObjectNode review() throws Exception {
        ObjectNode c = live(); c.put("category", "INDEPENDENT_REVIEW").put("mode", "REVIEW_ONLY");
        c.set("fixture", JSON.createObjectNode()); c.set("turns", JSON.createArrayNode());
        ((ObjectNode)c.path("expect")).set("orders", JSON.createObjectNode());
        c.set("reviewInput", JSON.createObjectNode().put("synthetic", true).put("originalUserRequest", "合成请求")
                .put("trustedOrder", "{\"id\":"+ORDER+",\"status\":\"RECEIVED\"}").put("trustedEligibility", "合成资格")
                .set("candidateAction", JSON.createObjectNode().put("orderId", Long.toString(ORDER)).put("reason", "不想要了")));
        ((ObjectNode)c.path("reviewInput")).set("policyEvidence", JSON.createObjectNode().put("fingerprint", "fp-runtime")
                .put("code", "SEVEN_DAY_NO_REASON").put("title", "七日无理由").put("clauseText", "合成政策七日内可申请退款。"));
        return c;
    }
    private static ObjectNode bindings() throws Exception {
        return (ObjectNode)JSON.readTree("{\"schemaVersion\":1,\"runId\":\"run-test\",\"caseId\":\"NORMAL-001\",\"trialId\":\"trial-test\",\"activeActor\":\"actor-a\",\"actors\":{\"actor-a\":{\"userId\":\"9007199254740993\",\"userToken\":\"private-token-sentinel\"},\"actor-b\":{\"userId\":\"9007199254740994\"}},\"orders\":{\"order-a\":{\"orderId\":\""+ORDER+"\",\"orderNo\":\"88776655\"}},\"products\":{\"product-a\":{\"productId\":\"9007199254742001\",\"skus\":{\"sku-a\":\"9007199254743001\"}}}}");
    }
    private static ObjectNode reviewBindings() throws Exception { ObjectNode b = bindings(); b.putNull("activeActor"); b.set("actors", JSON.createObjectNode()); b.set("products", JSON.createObjectNode()); return b; }
    private static final class ReplyModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>(); final String text;
        ReplyModel(String text) { this.text = text; }
        @Override public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            AiMessage message = new AiMessage(text);
            if (request.toolSpecifications()!=null && !request.toolSpecifications().isEmpty()
                    && request.messages().get(request.messages().size()-1) instanceof UserMessage
                    && request.messages().get(request.messages().size()-1).toString().contains("请退订单"))
                message = AiMessage.from(ToolExecutionRequest.builder().id("handoff-test").name("handoff_refund")
                        .arguments("{\"orderId\":"+ORDER+",\"reason\":\"不想要了\"}").build());
            else if (request.toolSpecifications()!=null && !request.toolSpecifications().isEmpty()
                    && request.messages().get(request.messages().size()-1) instanceof UserMessage
                    && request.messages().get(request.messages().size()-1).toString().contains("商品"))
                message = AiMessage.from(ToolExecutionRequest.builder().id("explanation-test").name("request_explanation").arguments("{\"orderId\":0}").build());
            return ChatResponse.builder().aiMessage(message).tokenUsage(new TokenUsage(3, 2)).build();
        }
    }
    private static final class FakeMcp {
        final List<ToolExecutionRequest> calls = new ArrayList<>(); final AtomicInteger closed = new AtomicInteger();
        boolean timeoutOnSubmit; boolean missingTools; RuntimeException getOrderError;
        final McpClient client = (McpClient)Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class}, (proxy, method, argv) -> switch (method.getName()) {
            case "listTools" -> missingTools ? List.of() : List.of("get_order", "list_user_orders", "get_logistics").stream().map(name -> ToolSpecification.builder().name(name).description(name).build()).toList();
            case "close" -> { closed.incrementAndGet(); yield null; }
            case "executeTool" -> execute((ToolExecutionRequest)argv[0]);
            default -> throw new AssertionError(method.getName());
        });
        int submits() { return (int)calls.stream().filter(c -> c.name().equals("submit_refund")).count(); }
        ToolExecutionResult execute(ToolExecutionRequest request) {
            calls.add(request);
            if (request.name().equals("get_order") && getOrderError != null) throw getOrderError;
            if (request.name().equals("submit_refund") && timeoutOnSubmit) throw new IllegalStateException("private-transport-sentinel");
            String text = switch (request.name()) {
                case "get_order" -> "{\"id\":"+ORDER+",\"status\":\"RECEIVED\",\"totalAmount\":39.8}";
                case "get_refund_eligibility" -> "{\"orderId\":"+ORDER+",\"orderStatus\":\"RECEIVED\",\"eligible\":true,\"refundExists\":false,\"refundableAmount\":39.80,\"catalogFingerprint\":\"fp-runtime\",\"policyCode\":\"SEVEN_DAY_NO_REASON\",\"policyTitle\":\"七日无理由\"}";
                case "list_policy_clauses" -> "{\"fingerprint\":\"fp-runtime\",\"clauses\":[{\"code\":\"SEVEN_DAY_NO_REASON\",\"title\":\"七日无理由\",\"clauseText\":\"七日内可申请退款。\"}]}";
                case "submit_refund" -> "{\"orderId\":"+ORDER+",\"eligible\":true,\"refundExists\":false,\"refundableAmount\":39.8}";
                case "list_on_shelf_products" -> "{\"total\":1,\"size\":20,\"current\":1,\"records\":[{\"id\":9007199254742001,\"status\":\"ON_SHELF\",\"name\":\"Fixture notebook\",\"description\":\"纸本商品\"}]}";
                case "get_product_detail" -> "{\"id\":9007199254742001,\"status\":\"ON_SHELF\",\"name\":\"Fixture notebook\",\"description\":\"纸本商品\",\"skus\":[{\"id\":9007199254743001,\"specs\":\"蓝色\",\"price\":19.90,\"stock\":5}]}";
                default -> throw new AssertionError(request.name());
            };
            return ToolExecutionResult.builder().isError(false).resultText(text).build();
        }
    }
}
