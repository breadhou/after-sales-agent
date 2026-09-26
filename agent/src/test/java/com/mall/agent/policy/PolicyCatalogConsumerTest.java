package com.mall.agent.policy;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyCatalogConsumerTest {

    private static final String CATALOG_V1 = """
            {"fingerprint":"catalog-v1","clauses":[
              {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款 v1","clauseText":"已发货时可申请整单退款；执行后立即完成。"},
              {"code":"QUALITY_ISSUE","title":"质量问题 v1","clauseText":"签收超过七天仍可因质量问题申请整单退款。"}
            ]}
            """;

    private static final String CATALOG_V2 = """
            {"fingerprint":"catalog-v2","clauses":[
              {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款 v2","clauseText":"已发货时可申请整单退款；执行后立即完成，政策目录 v2。"},
              {"code":"QUALITY_ISSUE","title":"质量问题 v2","clauseText":"签收超过七天仍可因质量问题申请整单退款，政策目录 v2。"}
            ]}
            """;

    @Test
    void switchesCompleteGenerationWhenFingerprintChanges() {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        PolicyCatalogConsumer consumer = consumer(calls,
                success(CATALOG_V1), success(CATALOG_V2));

        CatalogSnapshot first = consumer.refresh();
        PolicyEvidence firstEvidence = consumer.requireMatching(
                first, "catalog-v1", "SHIPPED_NOT_RECEIVED");

        assertEquals("catalog-v1", first.fingerprint());
        assertEquals("已发货退款 v1", firstEvidence.title());
        assertEquals("已发货时可申请整单退款；执行后立即完成。", firstEvidence.clauseText());
        assertEquals("catalog-v1", firstEvidence.fingerprint());

        CatalogSnapshot second = consumer.refresh();
        PolicyEvidence secondEvidence = consumer.requireMatching(
                second, "catalog-v2", "SHIPPED_NOT_RECEIVED");

        assertNotEquals(first.fingerprint(), second.fingerprint());
        assertEquals("已发货退款 v2", secondEvidence.title());
        assertEquals("已发货时可申请整单退款；执行后立即完成，政策目录 v2。",
                secondEvidence.clauseText());
        assertEquals("catalog-v2", secondEvidence.fingerprint());
        assertThrows(PolicyCatalogConsumer.PolicyCatalogException.class,
                () -> consumer.requireMatching(second, "catalog-v1", "SHIPPED_NOT_RECEIVED"));

        // A later refresh must not mutate the generation already pinned by this request.
        PolicyEvidence stillPinnedToFirst = consumer.requireMatching(
                first, "catalog-v1", "SHIPPED_NOT_RECEIVED");
        assertEquals("已发货退款 v1", stillPinnedToFirst.title());
        assertEquals("已发货时可申请整单退款；执行后立即完成。",
                stillPinnedToFirst.clauseText());

        assertEquals(2, calls.size(), "each generation must come from one catalog response");
        assertEquals(List.of("list_policy_clauses", "list_policy_clauses"),
                calls.stream().map(ToolExecutionRequest::name).toList());
        assertEquals(List.of("{}", "{}"),
                calls.stream().map(ToolExecutionRequest::arguments).toList());
    }

    @Test
    void rejectsDuplicateOrUnknownCode() {
        String duplicateCodes = """
                {"fingerprint":"catalog-duplicate","clauses":[
                  {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款","clauseText":"条款一。"},
                  {"code":"SHIPPED_NOT_RECEIVED","title":"另一个已发货退款","clauseText":"条款二。"}
                ]}
                """;
        PolicyCatalogConsumer duplicateConsumer = consumer(new ArrayList<>(), success(duplicateCodes));
        assertThrows(PolicyCatalogConsumer.PolicyCatalogException.class,
                duplicateConsumer::refresh);

        PolicyCatalogConsumer exactLookupConsumer = consumer(
                new ArrayList<>(), success(CATALOG_V1));
        CatalogSnapshot snapshot = exactLookupConsumer.refresh();
        // This is a plausible paraphrase of an existing code; it must not select by meaning.
        assertThrows(PolicyCatalogConsumer.PolicyCatalogException.class,
                () -> exactLookupConsumer.requireMatching(
                        snapshot, "catalog-v1", "DELIVERED_ORDER_REFUND"));
    }

    @Test
    void failedRefreshNeverReturnsOldEvidence() {
        List<InvalidResponse> invalidResponses = List.of(
                new InvalidResponse("MCP error", error(CATALOG_V2)),
                new InvalidResponse("null result", null),
                new InvalidResponse("null result text", resultWithNullText()),
                new InvalidResponse("blank result text", success("   ")),
                new InvalidResponse("malformed JSON", success("not JSON")),
                new InvalidResponse("valid JSON followed by trailing garbage",
                        success(CATALOG_V1 + "garbage")),
                new InvalidResponse("blank fingerprint", success("""
                        {"fingerprint":"  ","clauses":[
                          {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款","clauseText":"条款。"}
                        ]}
                        """)),
                new InvalidResponse("duplicate policy code", success("""
                        {"fingerprint":"catalog-duplicate","clauses":[
                          {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款","clauseText":"条款一。"},
                          {"code":"SHIPPED_NOT_RECEIVED","title":"另一个已发货退款","clauseText":"条款二。"}
                        ]}
                        """)),
                new InvalidResponse("blank title", success("""
                        {"fingerprint":"catalog-blank-title","clauses":[
                          {"code":"SHIPPED_NOT_RECEIVED","title":" ","clauseText":"条款。"}
                        ]}
                        """)),
                new InvalidResponse("blank clause text", success("""
                        {"fingerprint":"catalog-blank-body","clauses":[
                          {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款","clauseText":" "}
                        ]}
                        """)));

        for (InvalidResponse invalid : invalidResponses) {
            List<ToolExecutionRequest> calls = new ArrayList<>();
            PolicyCatalogConsumer consumer = consumer(calls,
                    success(CATALOG_V1), invalid.result());
            CatalogSnapshot oldSnapshot = consumer.refresh();
            assertEquals("catalog-v1", oldSnapshot.fingerprint(), invalid.description());

            assertThrows(PolicyCatalogConsumer.PolicyCatalogException.class,
                    consumer::refresh,
                    "failed refresh must not hand this request the cached generation: "
                            + invalid.description());
            assertEquals(2, calls.size(), invalid.description());
        }
    }

    @Test
    void orderChangeProducesNewGeneration() {
        String originalOrder = """
                {"fingerprint":"catalog-order-v1","clauses":[
                  {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款","clauseText":"已发货可申请退款。"},
                  {"code":"QUALITY_ISSUE","title":"质量问题","clauseText":"质量问题可申请退款。"}
                ]}
                """;
        String changedOrder = """
                {"fingerprint":"catalog-order-v2","clauses":[
                  {"code":"QUALITY_ISSUE","title":"质量问题","clauseText":"质量问题可申请退款。"},
                  {"code":"SHIPPED_NOT_RECEIVED","title":"已发货退款","clauseText":"已发货可申请退款。"}
                ]}
                """;
        PolicyCatalogConsumer consumer = consumer(new ArrayList<>(),
                success(originalOrder), success(changedOrder));

        CatalogSnapshot first = consumer.refresh();
        CatalogSnapshot second = consumer.refresh();

        assertNotEquals(first.fingerprint(), second.fingerprint());
        assertEquals("已发货可申请退款。", consumer.requireMatching(
                first, "catalog-order-v1", "SHIPPED_NOT_RECEIVED").clauseText());
        assertEquals("已发货可申请退款。", consumer.requireMatching(
                second, "catalog-order-v2", "SHIPPED_NOT_RECEIVED").clauseText());
        assertThrows(PolicyCatalogConsumer.PolicyCatalogException.class,
                () -> consumer.requireMatching(second, "catalog-order-v1", "SHIPPED_NOT_RECEIVED"));
    }

    private static PolicyCatalogConsumer consumer(List<ToolExecutionRequest> calls,
                                                  ToolExecutionResult... results) {
        Iterator<ToolExecutionResult> responses = Arrays.asList(results).iterator();
        Function<ToolExecutionRequest, ToolExecutionResult> toolCaller = request -> {
            calls.add(request);
            assertEquals("list_policy_clauses", request.name());
            assertEquals("{}", request.arguments());
            if (!responses.hasNext()) {
                throw new AssertionError("每次刷新应只调用一次 list_policy_clauses");
            }
            return responses.next();
        };
        return new PolicyCatalogConsumer(toolCaller);
    }

    private static ToolExecutionResult success(String text) {
        return ToolExecutionResult.builder().resultText(text).isError(false).build();
    }

    private static ToolExecutionResult error(String text) {
        return ToolExecutionResult.builder().resultText(text).isError(true).build();
    }

    private static ToolExecutionResult resultWithNullText() {
        return ToolExecutionResult.builder().resultTextSupplier(() -> null).isError(false).build();
    }

    private record InvalidResponse(String description, ToolExecutionResult result) {
    }
}
