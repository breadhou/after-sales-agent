package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.mcp.FakeSupermall;
import com.mall.agent.mcp.SupermallClient;
import com.mall.agent.mcp.SupermallException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProductToolsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listAlwaysForcesOnShelfAndBoundsPageSize() throws Exception {
        try (FakeSupermall fake = new FakeSupermall()) {
            ProductTools tools = new ProductTools(new SupermallClient(fake.baseUrl(), "test-user-token"));
            JsonNode page = MAPPER.readTree(tools.listOnShelfProducts(2, 999));
            assertEquals("/api/products?pageNum=2&pageSize=20&status=ON_SHELF", fake.receivedRequestTargets.get(0));
            assertEquals("GET", fake.receivedMethods.get(0));
            assertEquals(Set.of("records", "total", "size", "current"), fields(page));
            assertEquals(Set.of("id", "name", "description", "status"), fields(page.path("records").get(0)));
            assertEquals(2, page.path("current").asInt());
            assertEquals(20, page.path("size").asInt());
            assertFalse(page.toString().contains("merchantId"));
            assertFalse(page.toString().contains("minPrice"));
            assertFalse(page.toString().contains("skus"));
        }
    }

    @Test
    void detailKeepsStatusForCallerVerification() throws Exception {
        try (FakeSupermall fake = new FakeSupermall()) {
            ProductTools tools = new ProductTools(new SupermallClient(fake.baseUrl(), "test-user-token"));
            fake.respondWith(200, "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":101,"
                    + "\"name\":\"Test product\",\"description\":\"Current description\","
                    + "\"status\":\"OFF_SHELF\",\"minPrice\":12.50,\"totalStock\":4,"
                    + "\"skus\":[{\"id\":501,\"specs\":\"Blue\",\"price\":12.50,\"stock\":4,"
                    + "\"image\":\"https://example.test/sku.png\",\"merchantId\":7}],"
                    + "\"categoryId\":3,\"merchantId\":7}}");
            JsonNode detail = MAPPER.readTree(tools.getProductDetail(101L));
            assertEquals("/api/products/101", fake.receivedRequestTargets.get(0));
            assertEquals("GET", fake.receivedMethods.get(0));
            assertEquals(Set.of("id", "name", "description", "status", "minPrice", "totalStock", "skus"), fields(detail));
            assertEquals("OFF_SHELF", detail.path("status").asText());
            assertEquals(Set.of("id", "specs", "price", "stock"), fields(detail.path("skus").get(0)));
            assertFalse(detail.toString().contains("merchantId"));
            assertFalse(detail.toString().contains("categoryId"));
        }
    }

    @Test
    void nullOrErrorDetailIsToolError() throws Exception {
        try (FakeSupermall fake = new FakeSupermall()) {
            ProductTools tools = new ProductTools(new SupermallClient(fake.baseUrl(), "test-user-token"));
            fake.respondWith(200, "{\"code\":0,\"message\":\"ok\",\"data\":null}");
            assertThrows(SupermallException.class, () -> tools.getProductDetail(101L));
            fake.respondWith(200, "{\"code\":50000,\"message\":\"商品不存在\",\"data\":null}");
            assertEquals(50000, assertThrows(SupermallException.class,
                    () -> tools.getProductDetail(101L)).getCode());
            fake.respondWith(200, "{\"code\":0,\"message\":\"ok\","
                    + "\"data\":{\"id\":102,\"status\":\"ON_SHELF\"}}");
            assertThrows(SupermallException.class, () -> tools.getProductDetail(101L));
            fake.respondWith(401, "{\"code\":401,\"message\":\"unauthorized\",\"data\":null}");
            assertThrows(SupermallException.class, () -> tools.listOnShelfProducts(1, 20));
        }
    }

    @Test
    void productToolsRequireUserBearer() throws Exception {
        try (FakeSupermall fake = new FakeSupermall()) {
            assertThrows(IllegalArgumentException.class, () -> new SupermallClient(fake.baseUrl(), null));
            ProductTools tools = new ProductTools(new SupermallClient(fake.baseUrl(), "test-user-token"));
            tools.listOnShelfProducts(1, 10);
            tools.getProductDetail(101L);
            assertEquals(java.util.List.of("Bearer test-user-token", "Bearer test-user-token"), fake.receivedAuthHeaders);
        }
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
