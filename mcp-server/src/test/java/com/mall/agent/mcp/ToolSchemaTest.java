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
                "get_refund_eligibility", "list_policy_clauses", "submit_refund",
                "list_on_shelf_products", "get_product_detail"),
                tools.stream().map(McpServerMain.ToolDefinition::name).collect(Collectors.toSet()));
        assertEquals(8, tools.size());
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
    void submitRefundRequiresExactlyTheReviewedPair() throws Exception {
        var definition = McpServerMain.toolDefinitions().stream()
                .filter(tool -> tool.name().equals("submit_refund"))
                .findFirst().orElseThrow();
        JsonNode schema = MAPPER.readTree(definition.inputSchema());
        Set<String> fields = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);

        assertEquals(Set.of("orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"), fields);
        assertEquals("integer", schema.path("properties").path("orderId").path("type").asText());
        assertEquals("string", schema.path("properties").path("reason").path("type").asText());
        assertEquals("string", schema.path("properties").path("expectedCatalogFingerprint").path("type").asText());
        assertEquals("string", schema.path("properties").path("expectedPolicyCode").path("type").asText());
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        assertEquals(Set.of("orderId", "reason", "expectedCatalogFingerprint", "expectedPolicyCode"), required);
        assertFalse(schema.path("additionalProperties").asBoolean(true));
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

    @Test
    void productSchemasExposeOnlyReadArgumentsAndNoMerchantTool() throws Exception {
        var tools = McpServerMain.toolDefinitions();
        assertEquals(8, tools.size());
        assertFalse(tools.stream().anyMatch(tool -> tool.name().contains("merchant")
                || tool.name().contains("create_product") || tool.name().contains("update_product")));

        JsonNode list = MAPPER.readTree(tools.stream()
                .filter(tool -> tool.name().equals("list_on_shelf_products"))
                .findFirst().orElseThrow().inputSchema());
        JsonNode detail = MAPPER.readTree(tools.stream()
                .filter(tool -> tool.name().equals("get_product_detail"))
                .findFirst().orElseThrow().inputSchema());
        Set<String> listFields = new HashSet<>();
        list.path("properties").fieldNames().forEachRemaining(listFields::add);
        Set<String> detailFields = new HashSet<>();
        detail.path("properties").fieldNames().forEachRemaining(detailFields::add);
        assertEquals(Set.of("pageNum", "pageSize"), listFields);
        assertEquals(Set.of("productId"), detailFields);
        assertFalse(list.path("properties").has("status"));
        assertFalse(list.path("properties").has("token"));
        assertFalse(list.path("additionalProperties").asBoolean(true));
        assertFalse(detail.path("additionalProperties").asBoolean(true));
    }
}
