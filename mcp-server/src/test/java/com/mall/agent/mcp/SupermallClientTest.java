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
        JsonNode data = client.post("/api/orders/9001/refund/execute", "{\"reason\":\"damaged\"}");

        assertEquals(9001, data.get("id").asLong());
        assertEquals("POST", fake.receivedMethods.get(0));
        assertEquals("Bearer test-token", fake.receivedAuthHeaders.get(0));
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
