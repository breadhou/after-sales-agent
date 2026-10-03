package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** External provider doubles only; EvaluationMain runs the actual worker and production flow. */
class EvaluationHarnessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ORDER = 9007199254741001L;
    private static final String CODE = "SEVEN_DAY_NO_REASON";
    private static final String FINGERPRINT = "synthetic-worker-catalog-v1";
    private static final String APPROVED = "{\"approved\":true,\"citedPolicyCode\":\"SEVEN_DAY_NO_REASON\",\"faults\":[]}";
    @TempDir Path temporary;

    @Test void allThirtyTwoPilotCasesUseTheActualJavaInputContract() throws Exception {
        List<ObjectNode> cases = pilot();
        assertEquals(32, cases.size());
        for (ObjectNode value : cases) {
            JsonNode business = CaseSpec.parse(value).document();
            assertFalse(business.has("expect"), value.path("caseId").textValue());
            assertFalse(business.has("manualRubric"), value.path("caseId").textValue());
        }
    }

    @Test void exportRealWorkerBaselineFourCounterexamplesAndControlledFailureEvidence() throws Exception {
        Path run = temporary.resolve("task11-synthetic");
        Path baseline = run.resolve("baseline");
        ObjectNode exported = execute(find("NORMAL-001"), baseline);
        assertEquals("COMPLETED", exported.path("worker").path("terminalEvidence").textValue());
        assertTrue(exported.path("worker").path("errorCategory").isNull(), exported.toString());
        assertTrue(count(exported, "CONFIRMATION", "COMPLETED", null) > 0);
        assertTrue(count(exported, "REVIEW", "COMPLETED", null) > 0);
        assertEquals(1, count(exported, "MCP", "CALLED", "submit_refund"));
        assertEquals(1, exported.path("after").path("orders").path("order-a").path("refundRows").size());
        for (String name : List.of("missing-confirmation", "deleted-review", "unbound-target", "missing-oracle")) {
            Path target = run.resolve(name);
            copyTree(baseline, target);
            ObjectNode changed = (ObjectNode)JSON.readTree(Files.readString(target.resolve("trial.json")));
            if (name.equals("missing-confirmation")) removeEvents(changed, target, "CONFIRMATION", "COMPLETED");
            else if (name.equals("deleted-review")) {
                removeEvents(changed, target, "REVIEW", null);
                ObjectNode privateManifest = (ObjectNode)JSON.readTree(Files.readString(target.resolve("worker/evidence.json")));
                ArrayNode kept = JSON.createArrayNode();
                privateManifest.path("records").forEach(row -> { if (!row.path("kind").asText().equals("REVIEW_OUTCOME")) kept.add(row); });
                privateManifest.set("records", kept); write(target.resolve("worker/evidence.json"), privateManifest);
            } else if (name.equals("unbound-target")) changed.path("events").forEach(event -> {
                if (event.path("phase").asText().equals("MCP") && event.path("tool").asText().equals("submit_refund"))
                    ((ObjectNode)event).put("target", "UNBOUND");
            });
            else changed.putNull("after");
            saveEvents(changed, target); write(target.resolve("trial.json"), changed);
        }
        ObjectNode beforeFailure = execute(find("FAULT-003"), run.resolve("before-send"));
        assertEquals("NOT_SENT", beforeFailure.path("worker").path("terminalEvidence").textValue());
        assertEquals("UNRESOLVED_WRITE", beforeFailure.path("worker").path("errorCategory").textValue());
        assertEquals(1, count(beforeFailure, "MCP", "SCRIPTED", "submit_refund"));
        assertEquals(0, count(beforeFailure, "MCP", "CALLED", "submit_refund"));
        assertEquals(0, beforeFailure.path("after").path("orders").path("order-a").path("refundRows").size());
        ObjectNode afterFailure = execute(find("FAULT-004"), run.resolve("after-response"));
        assertEquals("COMPLETED", afterFailure.path("worker").path("terminalEvidence").textValue());
        assertEquals("UNRESOLVED_WRITE", afterFailure.path("worker").path("errorCategory").textValue());
        assertEquals(1, afterFailure.path("after").path("orders").path("order-a").path("refundRows").size());
        assertEquals(1, count(afterFailure, "MCP", "CALLED", "submit_refund"));
        ObjectNode unknownTool = execute(find("ADVERSARIAL-021"), run.resolve("unknown-tool"));
        assertEquals("NOT_SENT", unknownTool.path("worker").path("terminalEvidence").textValue());
        assertEquals(0, count(unknownTool, "MCP", "CALLED", "submit_refund"));
        assertEquals(0, unknownTool.path("after").path("orders").path("order-a").path("refundRows").size());
        Path destination = root().resolve("agent/target/evaluation-counterexamples");
        resetOwnedExport(destination); copyTree(run, destination);
        write(destination.resolve("provenance.json"), JSON.createObjectNode().put("synthetic", true)
                .put("worker", "EvaluationMain.run / TrialExecutor")
                .put("providers", "fake ChatModel / fake McpClient; no model/API/helper/database calls")
                .put("oracle", "synthetic state from fake MCP; not backend evidence")
                .put("manualAudit", "none; no human semantic audit"));
        System.out.println("TASK11_SYNTHETIC_EXPORT=" + destination);
    }

    private ObjectNode execute(ObjectNode value, Path trial) throws Exception {
        Files.createDirectories(trial); FakeMcp mcp = new FakeMcp();
        write(trial.resolve("case.json"), value);
        String trialId = trial.getFileName().toString();
        write(trial.resolve("bindings.json"), bindings(value.path("caseId").textValue(), trialId));
        write(trial.resolve("products.json"), JSON.createObjectNode().put("product-a", 9007199254742001L));
        write(trial.resolve("worker-config.json"), JSON.createObjectNode().put("schemaVersion", 1).put("runId", "task11-synthetic")
                .put("caseId", value.path("caseId").textValue()).put("trialId", trialId)
                .put("caseFile", "case.json").put("bindingFile", "bindings.json").put("productManifest", "products.json")
                .put("workDir", "worker").put("requestAllowance", 12).put("reportedTokenAllowance", 10000));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EvaluationMain.run(trial.resolve("worker-config.json"), temporary, alias -> {
            assertEquals("actor-a", alias); return mcp.client;
        }, new FakeModel(), new PrintStream(output, true, StandardCharsets.UTF_8));
        ObjectNode worker = (ObjectNode)JSON.readTree(output.toString(StandardCharsets.UTF_8));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(Long.toString(ORDER)));
        assertTrue(mcp.submitCount <= 1, "No write retry may reach the external boundary");
        ObjectNode envelope = JSON.createObjectNode(); envelope.set("case", value);
        envelope.set("before", oracle(false, "NOT_SENT"));
        envelope.set("after", oracle(mcp.refunded, worker.path("terminalEvidence").textValue()));
        envelope.set("worker", worker); envelope.set("events", readEvents(trial.resolve("worker/events.jsonl")));
        write(trial.resolve("trial.json"), envelope); return envelope;
    }
    private static ObjectNode oracle(boolean refunded, String terminal) {
        ObjectNode order = JSON.createObjectNode().put("orderStatus", refunded ? "REFUNDED" : "RECEIVED")
                .put("paidAmount", "39.80").put("ownerMatches", true);
        ArrayNode rows = JSON.createArrayNode();
        if (refunded) rows.add(JSON.createObjectNode().put("status", "REFUNDED").put("amount", "39.80").put("ownerMatches", true));
        order.set("refundRows", rows);
        return JSON.createObjectNode().put("terminalEvidence", terminal).set("orders", JSON.createObjectNode().set("order-a", order));
    }
    private static ObjectNode bindings(String caseId, String trialId) {
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("runId", "task11-synthetic")
                .put("caseId", caseId).put("trialId", trialId).put("activeActor", "actor-a");
        result.set("actors", JSON.createObjectNode().set("actor-a", JSON.createObjectNode()
                .put("userId", "9007199254740993").put("userToken", "fake-offline-token-not-a-credential")));
        result.set("orders", JSON.createObjectNode().set("order-a", JSON.createObjectNode()
                .put("orderId", Long.toString(ORDER)).put("orderNo", "SYNTHETIC-ORDER")));
        result.set("products", JSON.createObjectNode().set("product-a", JSON.createObjectNode()
                .put("productId", "9007199254742001").set("skus", JSON.createObjectNode().put("sku-a", "9007199254743001"))));
        return result;
    }
    private static final class FakeModel implements ChatModel {
        @Override public ChatResponse doChat(ChatRequest request) {
            AiMessage message = AiMessage.from(APPROVED);
            if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
                message = AiMessage.from("请确认退款申请。");
                if (request.messages().get(request.messages().size() - 1) instanceof UserMessage)
                    message = AiMessage.from(ToolExecutionRequest.builder().id("synthetic-handoff").name("handoff_refund")
                            .arguments("{\"orderId\":" + ORDER + ",\"reason\":\"买错了\"}").build());
            }
            return ChatResponse.builder().aiMessage(message).tokenUsage(new TokenUsage(3, 2)).build();
        }
    }
    private static final class FakeMcp {
        boolean refunded; int submitCount;
        final McpClient client = (McpClient)Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listTools" -> List.of("get_order", "list_user_orders", "get_logistics").stream()
                            .map(name -> ToolSpecification.builder().name(name).description(name).build()).toList();
                    case "close" -> null;
                    case "executeTool" -> execute((ToolExecutionRequest)arguments[0]);
                    default -> throw new AssertionError(method.getName());
                });
        ToolExecutionResult execute(ToolExecutionRequest request) throws Exception {
            JsonNode arguments = JSON.readTree(request.arguments());
            if (!request.name().equals("list_policy_clauses")) assertEquals(ORDER, arguments.path("orderId").longValue());
            String response = switch (request.name()) {
                case "get_order" -> "{\"id\":" + ORDER + ",\"status\":\"RECEIVED\",\"totalAmount\":39.80}";
                case "get_refund_eligibility" -> "{\"orderId\":" + ORDER + ",\"orderStatus\":\"RECEIVED\",\"eligible\":true,"
                        + "\"refundExists\":false,\"refundableAmount\":39.80,\"catalogFingerprint\":\"" + FINGERPRINT
                        + "\",\"policyCode\":\"" + CODE + "\",\"policyTitle\":\"合成七日政策\"}";
                case "list_policy_clauses" -> "{\"fingerprint\":\"" + FINGERPRINT + "\",\"clauses\":[{\"code\":\"" + CODE
                        + "\",\"title\":\"合成七日政策\",\"clauseText\":\"合成政策：已签收完整天数不超过7时可申请整单退款。\"}]}";
                case "submit_refund" -> {
                    assertEquals(Set.of("orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"), fields(arguments));
                    assertEquals("买错了", arguments.path("reason").textValue());
                    assertEquals(FINGERPRINT, arguments.path("expectedCatalogFingerprint").textValue());
                    assertEquals(CODE, arguments.path("expectedPolicyCode").textValue());
                    refunded = true; submitCount++;
                    yield "{\"orderId\":" + ORDER + ",\"eligible\":true,\"refundExists\":false,\"refundableAmount\":39.80}";
                }
                default -> throw new AssertionError(request.name());
            };
            return ToolExecutionResult.builder().isError(false).resultText(response).build();
        }
    }
    private static List<ObjectNode> pilot() throws Exception {
        Path directory = root().resolve("eval/scenarios/v1");
        assertTrue(Files.isRegularFile(directory.resolve("manifest.pilot.json")), "Task11 pilot manifest is missing");
        List<ObjectNode> cases = new ArrayList<>();
        JsonNode manifest = JSON.readTree(Files.readString(directory.resolve("manifest.pilot.json")));
        for (JsonNode file : manifest.path("files")) for (String line : Files.readAllLines(directory.resolve(file.path("path").textValue())))
            cases.add((ObjectNode)JSON.readTree(line));
        return cases;
    }
    private static ObjectNode find(String id) throws Exception { return pilot().stream().filter(c -> c.path("caseId").asText().equals(id)).findFirst().orElseThrow(); }
    private static Path root() { Path p = Path.of("").toAbsolutePath(); return p.getFileName().toString().equals("agent") ? p.getParent() : p; }
    private static Set<String> fields(JsonNode node) { Set<String> names = new java.util.HashSet<>(); node.fieldNames().forEachRemaining(names::add); return names; }
    private static long count(JsonNode envelope, String phase, String status, String tool) {
        return java.util.stream.StreamSupport.stream(envelope.path("events").spliterator(), false).filter(e ->
                e.path("phase").asText().equals(phase) && e.path("status").asText().equals(status)
                        && (tool == null || e.path("tool").asText().equals(tool))).count();
    }
    private static ArrayNode readEvents(Path file) throws Exception { ArrayNode out = JSON.createArrayNode(); for (String line : Files.readAllLines(file)) if (!line.isBlank()) out.add(JSON.readTree(line)); return out; }
    private static void saveEvents(ObjectNode envelope, Path directory) throws Exception {
        StringBuilder text = new StringBuilder(); envelope.path("events").forEach(e -> text.append(e).append('\n'));
        Files.writeString(directory.resolve("worker/events.jsonl"), text, StandardCharsets.UTF_8);
    }
    private static void removeEvents(ObjectNode envelope, Path directory, String phase, String status) throws Exception {
        ArrayNode events = JSON.createArrayNode(); Map<Integer, Integer> resequenced = new HashMap<>();
        for (JsonNode event : envelope.path("events")) {
            if (event.path("phase").asText().equals(phase) && (status == null || event.path("status").asText().equals(status))) continue;
            int prior = event.path("sequence").asInt();
            ((ObjectNode)event).put("sequence", events.size() + 1); resequenced.put(prior, events.size() + 1); events.add(event);
        }
        envelope.set("events", events);
        ObjectNode manifest = (ObjectNode)JSON.readTree(Files.readString(directory.resolve("worker/evidence.json")));
        for (JsonNode row : manifest.path("records")) if (row.path("kind").asText().equals("SOURCE") && row.path("callId").asText().startsWith("source-")) {
            int prior = Integer.parseInt(row.path("callId").asText().substring(7));
            assertTrue(resequenced.containsKey(prior), "Only the intended proof may be removed");
            ((ObjectNode)row).put("callId", "source-" + resequenced.get(prior));
        }
        write(directory.resolve("worker/evidence.json"), manifest);
    }
    private static void write(Path file, JsonNode value) throws Exception { Files.writeString(file, value.toString(), StandardCharsets.UTF_8); }
    private static void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) { for (Path path : paths.toList()) {
            Path target = destination.resolve(source.relativize(path));
            if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
        } }
    }
    private static void resetOwnedExport(Path destination) throws Exception {
        Path allowed = root().resolve("agent/target").toAbsolutePath().normalize();
        assertEquals(allowed.resolve("evaluation-counterexamples"), destination.toAbsolutePath().normalize());
        assertFalse(Files.isSymbolicLink(destination));
        if (Files.exists(destination)) try (var paths = Files.walk(destination)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
