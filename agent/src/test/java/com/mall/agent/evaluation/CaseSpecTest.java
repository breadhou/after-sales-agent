package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseSpecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sharedCaseFixturesHaveTheSameAcceptRejectDecisionsAsPython() throws IOException {
        JsonNode fixtures = MAPPER.readTree(Files.readString(sharedFixturePath()));
        JsonNode templates = fixtures.path("templates");

        for (JsonNode fixture : fixtures.path("cases")) {
            JsonNode value = applyPatches(templates.path(fixture.path("template").asText()),
                    fixture.path("patches"));
            boolean accepted = fixture.path("accepted").asBoolean();
            String name = fixture.path("id").asText();
            if (accepted) {
                CaseSpec parsed = CaseSpec.parse(value);
                ObjectNode expected = (ObjectNode) value.deepCopy();
                expected.remove("expect");
                expected.remove("manualRubric");
                assertEquals(expected, parsed.document(), name);
            } else {
                assertThrows(IllegalArgumentException.class, () -> CaseSpec.parse(value), name);
            }
        }
    }

    @Test
    void mcpContractDirectCallRequiresNoneAndRealBackendComponents() throws IOException {
        JsonNode direct = template("mcpContract");
        CaseSpec.parse(direct);

        ObjectNode wrongPoint = (ObjectNode) direct.deepCopy();
        ((ObjectNode) wrongPoint.path("control")).put("point", "MCP_BEFORE_REQUEST");
        assertThrows(IllegalArgumentException.class, () -> CaseSpec.parse(wrongPoint));

        ObjectNode substitutedModel = (ObjectNode) direct.deepCopy();
        ((ObjectNode) substitutedModel.path("control").path("components")).put("DIALOGUE", "SUBSTITUTED");
        assertThrows(IllegalArgumentException.class, () -> CaseSpec.parse(substitutedModel));
    }

    @Test
    void documentExcludesEvaluatorLabelsAndReturnsDefensiveCopies() throws IOException {
        JsonNode value = template("live");
        CaseSpec parsed = CaseSpec.parse(value);

        JsonNode first = parsed.document();
        assertTrue(first.has("fixture"));
        assertFalse(first.has("expect"));
        assertFalse(first.has("manualRubric"));
        assertTrue(value.has("expect"));
        assertTrue(value.has("manualRubric"));
        assertNotSame(first, parsed.document());

        ((ObjectNode) first.path("fixture")).put("activeActor", "mutated");
        assertEquals("actor-a", parsed.document().path("fixture").path("activeActor").asText());
        ((ObjectNode) value.path("fixture")).put("activeActor", "source-mutated-after-parse");
        assertEquals("actor-a", parsed.document().path("fixture").path("activeActor").asText());
    }

    @Test
    void bigintOrderIdRemainsAnExactStringInTheBusinessDocument() throws IOException {
        JsonNode value = template("review");
        CaseSpec parsed = CaseSpec.parse(value);
        assertEquals("9007199254740993", parsed.document().path("reviewInput")
                .path("candidateAction").path("orderId").textValue());
    }

    private JsonNode template(String name) throws IOException {
        return MAPPER.readTree(Files.readString(sharedFixturePath())).path("templates").path(name);
    }

    private static Path sharedFixturePath() {
        Path direct = Path.of("eval", "fixtures", "case-contract.json");
        if (Files.isRegularFile(direct)) return direct;
        Path moduleRelative = Path.of("..", "eval", "fixtures", "case-contract.json");
        if (Files.isRegularFile(moduleRelative)) return moduleRelative;
        throw new IllegalStateException("Shared evaluation contract fixture is missing");
    }

    private static JsonNode applyPatches(JsonNode source, JsonNode patches) {
        JsonNode result = source.deepCopy();
        for (JsonNode patch : patches) {
            ArrayNode path = (ArrayNode) patch.path("path");
            JsonNode parent = result;
            for (int index = 0; index < path.size() - 1; index++) {
                String component = path.get(index).asText();
                parent = parent.isArray() ? parent.get(Integer.parseInt(component)) : parent.path(component);
            }
            String key = path.get(path.size() - 1).asText();
            String operation = patch.path("op").asText();
            if (parent.isArray()) {
                ArrayNode array = (ArrayNode) parent;
                int position = Integer.parseInt(key);
                if ("set".equals(operation)) array.set(position, patch.path("value").deepCopy());
                else if ("add".equals(operation)) array.insert(position, patch.path("value").deepCopy());
                else if ("remove".equals(operation)) array.remove(position);
                else throw new IllegalArgumentException("Unknown shared fixture patch operation");
            } else {
                ObjectNode object = (ObjectNode) parent;
                if ("set".equals(operation) || "add".equals(operation)) {
                    object.set(key, patch.path("value").deepCopy());
                } else if ("remove".equals(operation)) {
                    object.remove(key);
                } else {
                    throw new IllegalArgumentException("Unknown shared fixture patch operation");
                }
            }
        }
        return result;
    }
}
