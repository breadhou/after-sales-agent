package com.mall.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupermallClientTest {

    private FakeSupermall fake;
    private SupermallClient client;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeSupermall();
        client = new SupermallClient(fake.baseUrl(), "test-token");
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void get_unwrapsTheCurrentResultEnvelope() {
        JsonNode data = client.get("/api/orders/9001");

        assertEquals(9001, data.get("id").asLong());
        assertEquals("RECEIVED", data.get("status").asText());
    }

    @Test
    void get_sendsTheConstructorTokenAsBearerAuthorization() {
        client.get("/api/orders/9001");

        assertEquals("Bearer test-token", fake.receivedAuthHeaders.get(0));
        assertEquals("/api/orders/9001", fake.receivedPaths.get(0));
    }

    @Test
    void post_sendsAnAuthenticatedPostAndUnwrapsTheResultEnvelope() {
        String requestBody = "{\"reason\":\"商品损坏\"}";
        JsonNode data = client.post("/api/orders/9001/refund/execute", requestBody);

        assertEquals(9001, data.get("orderId").asLong());
        assertEquals("POST", fake.receivedMethods.get(0));
        assertEquals("Bearer test-token", fake.receivedAuthHeaders.get(0));
        assertEquals(requestBody, fake.lastRequestBody);
        assertTrue(fake.lastContentType.startsWith("application/json"));
    }

    @Test
    void get_usesOrderDetailDefaultWithFieldsThatToolsMustTrim() {
        JsonNode data = client.get("/api/orders/9001");

        assertEquals("SN9001", data.get("orderNo").asText());
        assertEquals("RECEIVED", data.get("status").asText());
        assertEquals(10001, data.get("userId").asLong());
        assertEquals(20001, data.get("addressId").asLong());
    }

    @Test
    void get_usesOrderListDefaultWithRecords() {
        JsonNode data = client.get("/api/orders?pageNum=1&pageSize=20");

        assertEquals("SN9001", data.get("records").get(0).get("orderNo").asText());
    }

    @Test
    void get_usesLogisticsDefaultForTheLogisticsRoute() {
        JsonNode data = client.get("/api/orders/9001/logistics");

        assertEquals("SF Express", data.get("company").asText());
        assertEquals("SF1234567890", data.get("trackingNo").asText());
    }

    @Test
    void get_usesRefundEligibilityDefaultForTheEligibilityRoute() {
        JsonNode data = client.get("/api/orders/9001/refund-eligibility");

        assertTrue(data.get("eligible").asBoolean());
        assertEquals("SEVEN_DAY_NO_REASON", data.get("policyCode").asText());
        assertEquals(199.99, data.get("refundableAmount").asDouble());
    }

    @Test
    void get_usesPolicyCatalogDefaultForThePoliciesRoute() {
        JsonNode data = client.get("/api/after-sales/policies");

        assertEquals("fake-policy-catalog-v1", data.get("fingerprint").asText());
        assertEquals(3, data.get("clauses").size());
        assertEquals("SEVEN_DAY_NO_REASON", data.get("clauses").get(0).get("code").asText());
        assertEquals("SHIPPED_NOT_RECEIVED", data.get("clauses").get(1).get("code").asText());
        assertEquals("QUALITY_ISSUE", data.get("clauses").get(2).get("code").asText());
    }

    @Test
    void get_exposesBusinessCodeAndMessageFromNonzeroResult() {
        fake.respondWith(200, "{\"code\":50000,\"message\":\"订单不存在\",\"data\":null}");

        SupermallException exception = assertThrows(
                SupermallException.class, () -> client.get("/api/orders/404"));

        assertEquals(50000, exception.getCode());
        assertEquals("订单不存在", exception.getMessage());
    }

    @Test
    void constructor_rejectsBlankToken() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupermallClient(fake.baseUrl(), "  "));
    }

    @Test
    void get_mapsHttpFailureWithoutExposingResponseBody() {
        fake.respondWith(503, "database password=top-secret");

        SupermallException exception = assertThrows(
                SupermallException.class, () -> client.get("/api/orders/9001"));

        assertEquals(-3, exception.getCode());
        assertEquals("售后系统暂时不可用，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("top-secret"));
    }

    @Test
    void get_mapsConnectionFailureWithoutExposingTransportDetails() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        SupermallClient unavailable = new SupermallClient("http://localhost:" + unusedPort, "test-token");

        SupermallException exception = assertThrows(
                SupermallException.class, () -> unavailable.get("/api/orders/9001"));

        assertEquals(-2, exception.getCode());
        assertEquals("售后系统暂时不可用，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("localhost"));
    }
}
