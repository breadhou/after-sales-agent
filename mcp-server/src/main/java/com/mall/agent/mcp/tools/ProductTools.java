package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.mcp.SupermallClient;
import com.mall.agent.mcp.SupermallException;

/** Read-only current catalog facts for the trusted knowledge retriever. */
public class ProductTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int INVALID_ARGUMENT_CODE = 10000;
    private static final int INVALID_RESPONSE_CODE = -3;
    private final SupermallClient client;

    public ProductTools(SupermallClient client) {
        this.client = client;
    }

    /** Lists one page of current on-shelf products; the status filter is never caller-controlled. */
    public String listOnShelfProducts(int pageNum, int pageSize) {
        if (pageNum <= 0 || pageSize <= 0) {
            throw new SupermallException(INVALID_ARGUMENT_CODE, "参数不合法：页码和每页条数必须为正整数");
        }
        int boundedSize = Math.min(pageSize, 20);
        JsonNode data = client.get("/api/products?pageNum=" + pageNum
                + "&pageSize=" + boundedSize + "&status=ON_SHELF");
        if (!data.isObject() || !data.path("records").isArray()
                || !data.path("total").isNumber() || !data.path("size").isNumber()
                || !data.path("current").isNumber()) {
            throw invalidResponse();
        }
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode records = result.putArray("records");
        for (JsonNode record : data.path("records")) {
            records.add(pick(record, "id", "name", "description", "status"));
        }
        copy(data, result, "total", "size", "current");
        return result.toString();
    }

    /** Returns fresh detail including status so the caller can verify it before citing. */
    public String getProductDetail(long productId) {
        if (productId <= 0) {
            throw new SupermallException(INVALID_ARGUMENT_CODE, "参数不合法：productId 必须为正整数");
        }
        JsonNode data = client.get("/api/products/" + productId);
        if (!data.isObject() || !data.path("id").isIntegralNumber()
                || data.path("id").longValue() != productId || !data.path("status").isTextual()) {
            throw invalidResponse();
        }
        ObjectNode result = pick(data, "id", "name", "description", "status", "minPrice", "totalStock");
        JsonNode sourceSkus = data.path("skus");
        if (sourceSkus.isArray()) {
            ArrayNode skus = result.putArray("skus");
            for (JsonNode sku : sourceSkus) {
                skus.add(pick(sku, "id", "specs", "price", "stock"));
            }
        }
        return result.toString();
    }

    private static SupermallException invalidResponse() {
        return new SupermallException(INVALID_RESPONSE_CODE, "商品资料暂时不可用，请稍后重试");
    }

    private static ObjectNode pick(JsonNode source, String... fields) {
        ObjectNode result = MAPPER.createObjectNode();
        copy(source, result, fields);
        return result;
    }

    private static void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) {
                target.set(field, source.get(field));
            }
        }
    }
}
