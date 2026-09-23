package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.mcp.FakeSupermall;
import com.mall.agent.mcp.SupermallClient;
import com.mall.agent.mcp.ToolResults;
import com.mall.agent.mcp.SupermallException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FakeSupermall fake;
    private OrderTools tools;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeSupermall();
        tools = new OrderTools(new SupermallClient(fake.baseUrl(), "t"));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void getOrder_returnsOnlyTheModelFacingDetailFields() throws Exception {
        JsonNode result = MAPPER.readTree(tools.getOrder(9001L));

        assertEquals(Set.of("id", "orderNo", "status", "totalAmount", "createdAt"), fieldNames(result));
        assertEquals(9001, result.get("id").asLong());
        assertEquals("SN9001", result.get("orderNo").asText());
        assertEquals("RECEIVED", result.get("status").asText());
        assertFalse(result.toString().contains("userId"));
    }

    @Test
    void listUserOrders_encodesStatusAndTrimsEveryRecord() throws Exception {
        fake.respondWith(200, "{\"code\":0,\"message\":\"成功\",\"data\":{\"records\":["
                + "{\"id\":9001,\"orderNo\":\"SN9001\",\"totalAmount\":199.99,\"status\":\"RECEIVED\","
                + "\"createdAt\":\"2026-09-20T10:00:00\",\"itemCount\":1,\"userId\":10001},"
                + "{\"id\":9002,\"orderNo\":\"SN9002\",\"totalAmount\":88.50,\"status\":\"SHIPPED\","
                + "\"createdAt\":\"2026-09-21T10:00:00\",\"itemCount\":2,\"addressId\":20002}]}}" );
        JsonNode result = MAPPER.readTree(tools.listUserOrders("READY & 已签收"));

        assertEquals("/api/orders?pageNum=1&pageSize=20&status=READY+%26+%E5%B7%B2%E7%AD%BE%E6%94%B6",
                fake.receivedRequestTargets.get(0));
        JsonNode records = result.get("records");
        assertEquals(2, records.size());
        for (JsonNode record : records) {
            assertEquals(Set.of("id", "orderNo", "totalAmount", "status", "createdAt", "itemCount"), fieldNames(record));
        }
        assertEquals(9001, records.get(0).get("id").asLong());
        assertEquals("SN9001", records.get(0).get("orderNo").asText());
        assertEquals(199.99, records.get(0).get("totalAmount").asDouble());
        assertEquals(1, records.get(0).get("itemCount").asInt());
        assertEquals(9002, records.get(1).get("id").asLong());
        assertEquals("SN9002", records.get(1).get("orderNo").asText());
        assertEquals(88.50, records.get(1).get("totalAmount").asDouble());
        assertEquals(2, records.get(1).get("itemCount").asInt());
    }

    @Test
    void getLogistics_returnsOnlyTheModelFacingLogisticsFields() throws Exception {
        JsonNode result = MAPPER.readTree(tools.getLogistics(9001L));

        assertEquals(Set.of("company", "trackingNo", "status", "createdAt"), fieldNames(result));
        assertEquals("SF Express", result.get("company").asText());
        assertFalse(result.toString().contains("orderId"));
    }

    @Test
    void getLogistics_preservesMissingLogisticsBusinessErrorForTheMcpLayer() {
        fake.respondWith(200, "{\"code\":80002,\"message\":\"物流记录不存在\",\"data\":null}");

        SupermallException exception = assertThrows(SupermallException.class,
                () -> tools.getLogistics(9001L));

        assertEquals(80002, exception.getCode());
        assertEquals("物流记录不存在", exception.getMessage());
    }

    @Test
    void failure_preservesBusinessCodeAndMessageWithoutExceptionDetails() throws Exception {
        String result = ToolResults.failure(new SupermallException(50000, "订单不存在"));

        JsonNode failure = MAPPER.readTree(result);
        assertTrue(failure.get("error").asBoolean());
        assertEquals(50000, failure.get("code").asInt());
        assertEquals("订单不存在", failure.get("message").asText());
        assertFalse(result.contains("Exception"));
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
