package com.mall.agent.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.mcp.FakeSupermall;
import com.mall.agent.mcp.SupermallClient;
import com.mall.agent.mcp.SupermallException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FakeSupermall fake;
    private RefundTools tools;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeSupermall();
        tools = new RefundTools(new SupermallClient(fake.baseUrl(), "t"));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void getRefundEligibility_exposesOnlyGroundedDecisionFields() throws Exception {
        fake.respondWith(200, "{\"code\":0,\"message\":\"成功\",\"data\":{"
                + "\"eligible\":true,\"reason\":null,\"policyCode\":\"SEVEN_DAY_NO_REASON\","
                + "\"policyTitle\":\"签收 7 天内整单退款\",\"refundableAmount\":199.99,"
                + "\"refundExists\":false,\"internalReviewer\":\"staff-01\"}}");

        JsonNode result = MAPPER.readTree(tools.getRefundEligibility(9001L));

        assertEquals(Set.of("eligible", "reason", "policyCode", "policyTitle", "refundableAmount", "refundExists", "orderId"),
                fieldNames(result));
        assertTrue(result.get("eligible").asBoolean());
        assertEquals("SEVEN_DAY_NO_REASON", result.get("policyCode").asText());
        assertEquals("签收 7 天内整单退款", result.get("policyTitle").asText());
        assertEquals(199.99, result.get("refundableAmount").asDouble());
        assertEquals(9001L, result.get("orderId").asLong());
    }

    @Test
    void submitRefund_sendsOnlyUtf8ReasonAndPreservesPendingIdempotencyState() throws Exception {
        fake.respondWith(200, "{\"code\":0,\"message\":\"成功\",\"data\":{"
                + "\"orderId\":9001,\"eligible\":false,\"reason\":\"该订单已有退款申请在处理中\","
                + "\"policyCode\":null,\"policyTitle\":null,"
                + "\"refundableAmount\":199.99,\"refundExists\":true}}");

        JsonNode result = MAPPER.readTree(tools.submitRefund(9001L, "不想要了，商品完好"));
        JsonNode request = MAPPER.readTree(fake.lastRequestBody);

        assertEquals("/api/orders/9001/refund/execute", fake.receivedPaths.get(0));
        assertEquals("POST", fake.receivedMethods.get(0));
        assertEquals(Set.of("reason"), fieldNames(request));
        assertEquals("不想要了，商品完好", request.get("reason").asText());
        assertTrue(fake.lastContentType.toLowerCase().startsWith("application/json"));
        assertTrue(fake.lastContentType.toLowerCase().contains("charset=utf-8"));
        assertEquals(9001L, result.get("orderId").asLong());
        assertTrue(result.get("refundExists").asBoolean());
        assertEquals(false, result.get("eligible").asBoolean());
        assertEquals("该订单已有退款申请在处理中", result.get("reason").asText());
        assertTrue(result.get("policyCode").isNull());
        assertTrue(result.get("policyTitle").isNull());
        assertEquals(199.99, result.get("refundableAmount").asDouble());
        assertFalse(result.has("refundStatus"));
    }

    @Test
    void submitRefund_propagatesBusinessExceptionForTheMcpLayer() {
        fake.respondWith(200, "{\"code\":80001,\"message\":\"订单不满足退款条件\",\"data\":null}");

        SupermallException exception = assertThrows(SupermallException.class,
                () -> tools.submitRefund(9001L, "不想要了"));

        assertEquals(80001, exception.getCode());
        assertEquals("订单不满足退款条件", exception.getMessage());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
