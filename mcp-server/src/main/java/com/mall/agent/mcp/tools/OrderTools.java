package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.mcp.SupermallClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Provides trimmed order data for model decisions. */
public class OrderTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SupermallClient client;

    public OrderTools(SupermallClient client) {
        this.client = client;
    }

    /** Gets a single order without internal identifiers or item details. */
    public String getOrder(Long orderId) {
        return pick(client.get("/api/orders/" + orderId),
                "id", "orderNo", "status", "totalAmount", "createdAt").toString();
    }

    /** Lists up to twenty current-user orders, optionally filtered by status. */
    public String listUserOrders(String status) {
        String path = "/api/orders?pageNum=1&pageSize=20";
        if (status != null && !status.isBlank()) {
            path += "&status=" + URLEncoder.encode(status, StandardCharsets.UTF_8);
        }
        JsonNode data = client.get(path);
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode records = result.putArray("records");
        JsonNode sourceRecords = data.path("records");
        if (sourceRecords.isArray()) {
            for (JsonNode record : sourceRecords) {
                records.add(pick(record,
                        "id", "orderNo", "totalAmount", "status", "createdAt", "itemCount"));
            }
        }
        return result.toString();
    }

    /** Gets logistics data without order identifiers. */
    public String getLogistics(Long orderId) {
        return pick(client.get("/api/orders/" + orderId + "/logistics"),
                "company", "trackingNo", "status", "createdAt").toString();
    }

    private ObjectNode pick(JsonNode source, String... fields) {
        ObjectNode result = MAPPER.createObjectNode();
        for (String field : fields) {
            if (source.has(field)) {
                result.set(field, source.get(field));
            }
        }
        return result;
    }
}
