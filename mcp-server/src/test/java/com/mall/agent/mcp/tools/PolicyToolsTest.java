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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FakeSupermall fake;
    private PolicyTools tools;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeSupermall();
        tools = new PolicyTools(new SupermallClient(fake.baseUrl(), "t"));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void listPolicyClauses_returnsTheUnwrappedCatalogWithFingerprintAndCompleteClauses() throws Exception {
        JsonNode result = MAPPER.readTree(tools.listPolicyClauses());

        assertEquals("fake-policy-catalog-v1", result.get("fingerprint").asText());
        assertFalse(result.has("code"));
        assertFalse(result.has("message"));
        assertFalse(result.has("data"));
        JsonNode clauses = result.get("clauses");
        assertEquals(3, clauses.size());
        assertClause(clauses.get(0), "SEVEN_DAY_NO_REASON", "已签收（完整天数不超过 7）整单退款",
                "订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；计数不超过 7 天可申请整单退款，退款执行后立即完成。");
        assertClause(clauses.get(1), "SHIPPED_NOT_RECEIVED", "已发货或已送达整单退款",
                "订单状态为已发货或已送达时，可申请整单退款；退款执行后立即完成。");
        assertClause(clauses.get(2), "QUALITY_ISSUE", "已签收（完整天数超过 7）整单退款",
                "订单状态为已签收时，系统从订单创建时间起每满 24 小时计 1 天，不足 24 小时的余数不计；计数超过 7 天仍可申请整单退款，退款执行后立即完成。");
    }

    @Test
    void listPolicyClauses_propagatesBusinessExceptionForTheMcpLayer() {
        fake.respondWith(200, "{\"code\":50000,\"message\":\"政策目录不可用\",\"data\":null}");

        SupermallException exception = assertThrows(SupermallException.class, tools::listPolicyClauses);

        assertEquals(50000, exception.getCode());
        assertEquals("政策目录不可用", exception.getMessage());
    }

    private void assertClause(JsonNode clause, String code, String title, String clauseText) {
        assertEquals(code, clause.get("code").asText());
        assertEquals(title, clause.get("title").asText());
        assertEquals(clauseText, clause.get("clauseText").asText());
    }
}
