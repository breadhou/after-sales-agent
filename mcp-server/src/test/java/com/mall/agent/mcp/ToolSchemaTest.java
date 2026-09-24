package com.mall.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyAdvertisedToolHasAUniqueNameDescriptionAndObjectSchema() throws Exception {
        var tools = McpServerMain.toolDefinitions();

        assertEquals(Set.of("get_order", "list_user_orders", "get_logistics",
                "get_refund_eligibility", "list_policy_clauses", "submit_refund"),
                tools.stream().map(McpServerMain.ToolDefinition::name).collect(Collectors.toSet()));
        assertEquals(6, tools.size());
        for (var tool : tools) {
            assertFalse(tool.description().isBlank(), tool.name());
            JsonNode schema = MAPPER.readTree(tool.inputSchema());
            assertEquals("object", schema.path("type").asText(), tool.name());
            assertTrue(schema.path("properties").isObject(), tool.name());
            assertFalse(schema.path("additionalProperties").asBoolean(true), tool.name());
            if (schema.path("properties").has("orderId")) {
                assertEquals("9223372036854775807",
                        schema.path("properties").path("orderId").path("maximum").asText(), tool.name());
            }
        }
    }

    @Test
    void submitRefundAdvertisesOnlyOrderIdAndReason() throws Exception {
        var definition = McpServerMain.toolDefinitions().stream()
                .filter(tool -> tool.name().equals("submit_refund"))
                .findFirst().orElseThrow();
        JsonNode schema = MAPPER.readTree(definition.inputSchema());
        Set<String> fields = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);

        assertEquals(Set.of("orderId", "reason"), fields);
        assertEquals("integer", schema.path("properties").path("orderId").path("type").asText());
        assertEquals("string", schema.path("properties").path("reason").path("type").asText());
        assertEquals(Set.of("orderId", "reason"), Set.of(
                schema.path("required").get(0).asText(), schema.path("required").get(1).asText()));
    }

    @Test
    void eligibilityDescriptionPreservesExecutionAndPendingBoundaries() {
        String description = McpServerMain.toolDefinitions().stream()
                .filter(tool -> tool.name().equals("get_refund_eligibility"))
                .findFirst().orElseThrow().description();

        assertTrue(description.contains("eligible=false"));
        assertTrue(description.contains("refundableAmount"));
        assertTrue(description.contains("观察值"));
        assertTrue(description.contains("refundExists"));
        assertTrue(description.contains("PENDING"));
    }
}
