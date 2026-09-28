package com.mall.agent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentProductIndexTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void onlyIndexesManifestIdsFromAllPages() {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        List<Long> firstPageIds = LongStream.rangeClosed(1, 20).boxed().toList();
        List<String> responses = new ArrayList<>();
        responses.add(page(1, 21, firstPageIds, Set.of(3L)));
        responses.add(page(2, 21, List.of(21L), Set.of()));
        responses.add(detail(2, "Current description 2", "ON_SHELF"));
        responses.add(detail(21, "Current description 21", "ON_SHELF"));
        Iterator<ToolExecutionResult> results = successes(responses);
        CurrentProductIndex index = new CurrentProductIndex(request -> {
            calls.add(request);
            return results.next();
        });
        Set<Long> allowedIds = Set.of(2L, 3L, 21L, 99L);

        CurrentProductIndex.ProductSnapshot snapshot = index.refresh(allowedIds);

        assertTrue(snapshot.complete());
        assertEquals(Set.of(2L, 21L), snapshot.productIds());
        assertTrue(snapshot.productIds().stream().allMatch(allowedIds::contains));
        assertEquals(21L, snapshot.total());
        assertEquals(2, snapshot.current());
        assertEquals(20, snapshot.size());
        assertEquals(2, calls.stream().filter(call -> call.name().equals("list_on_shelf_products")).count());
        assertEquals(List.of("{\"pageNum\":1,\"pageSize\":20}",
                        "{\"pageNum\":2,\"pageSize\":20}"),
                calls.stream().filter(call -> call.name().equals("list_on_shelf_products"))
                        .map(ToolExecutionRequest::arguments).toList());
        assertEquals(List.of(2L, 21L), calls.stream()
                .filter(call -> call.name().equals("get_product_detail"))
                .map(call -> Long.parseLong(call.arguments().replaceAll("\\D", ""))).toList());
        assertEquals("blue / medium", snapshot.evidenceByProductId().get(2L).skus().get(0).specs());
        assertEquals(0, snapshot.evidenceByProductId().get(2L).skus().get(0)
                .price().compareTo(new java.math.BigDecimal("12.50")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.productIds().add(303L));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.evidenceByProductId().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.evidenceByProductId().get(2L).skus().clear());
    }

    @Test
    void rejectsDuplicateOrNonAdvancingPages() {
        AtomicInteger pageCalls = new AtomicInteger();
        CurrentProductIndex duplicateIndex = new CurrentProductIndex(request -> {
            pageCalls.incrementAndGet();
            return success(page(1, 2, List.of(7L, 7L), Set.of()));
        });
        CurrentProductIndex.ProductSnapshot duplicate = duplicateIndex.refresh(Set.of(7L));
        assertFalse(duplicate.complete());
        assertEquals(Set.of(), duplicate.productIds());
        assertTrue(duplicateIndex.verifyCitation(duplicate, 7L).isEmpty());

        pageCalls.set(0);
        List<String> nonAdvancingPages = List.of(
                page(1, 21, LongStream.rangeClosed(1, 20).boxed().toList(), Set.of()),
                page(1, 21, List.of(21L), Set.of()));
        Iterator<ToolExecutionResult> responses = successes(nonAdvancingPages);
        CurrentProductIndex nonAdvancingIndex = new CurrentProductIndex(request -> {
            pageCalls.incrementAndGet();
            return responses.next();
        });
        CurrentProductIndex.ProductSnapshot nonAdvancing = nonAdvancingIndex.refresh(Set.of(21L));
        assertFalse(nonAdvancing.complete());
        assertEquals(Set.of(), nonAdvancing.productIds());
        assertEquals(2, pageCalls.get());

        pageCalls.set(0);
        List<String> truncatedPages = List.of(
                page(1, 21, LongStream.rangeClosed(1, 20).boxed().toList(), Set.of()),
                page(2, 21, List.of(), Set.of()));
        Iterator<ToolExecutionResult> truncatedResponses = successes(truncatedPages);
        CurrentProductIndex truncatedIndex = new CurrentProductIndex(request -> {
            pageCalls.incrementAndGet();
            return truncatedResponses.next();
        });
        CurrentProductIndex.ProductSnapshot truncated = truncatedIndex.refresh(Set.of(21L));
        assertFalse(truncated.complete(), "a successful but incomplete page must disable citations");
        assertEquals(Set.of(), truncated.productIds());
        assertTrue(truncatedIndex.verifyCitation(truncated, 21L).isEmpty());
        assertTrue(pageCalls.get() <= 100);

        pageCalls.set(0);
        CurrentProductIndex oversizedIndex = new CurrentProductIndex(request -> {
            pageCalls.incrementAndGet();
            return success(page(1, 2001, LongStream.rangeClosed(1, 20).boxed().toList(), Set.of()));
        });
        CurrentProductIndex.ProductSnapshot oversized = oversizedIndex.refresh(Set.of(1L));
        assertFalse(oversized.complete());
        assertEquals(Set.of(), oversized.productIds());
        assertTrue(pageCalls.get() <= 100);
        assertEquals(1, pageCalls.get(), "page totals over the bound are rejected before page 2");

        for (String malformedPage : List.of(
                "{\"records\":[{\"name\":\"No ID\",\"description\":\"Description\",\"status\":\"ON_SHELF\"}],\"total\":1,\"size\":20,\"current\":1}",
                "{\"records\":[{\"id\":9,\"name\":\"No status\",\"description\":\"Description\"}],\"total\":1,\"size\":20,\"current\":1}")) {
            CurrentProductIndex malformedIndex = new CurrentProductIndex(request -> success(malformedPage));
            CurrentProductIndex.ProductSnapshot malformed = malformedIndex.refresh(Set.of(9L));
            assertFalse(malformed.complete());
            assertEquals(Set.of(), malformed.productIds());
            assertTrue(malformedIndex.verifyCitation(malformed, 9L).isEmpty());
        }
        assertTrue(pageCalls.get() <= 100);
    }

    @Test
    void offShelfOrNullDetailDropsCitation() {
        List<String> responses = new ArrayList<>();
        responses.add(page(1, 4, List.of(10L, 11L, 12L, 13L), Set.of()));
        responses.add(detail(10, "Cached description 10", "ON_SHELF"));
        responses.add(detail(11, "Cached description 11", "ON_SHELF"));
        responses.add(detail(12, "Cached description 12", "ON_SHELF"));
        responses.add(detail(13, "Cached description 13", "ON_SHELF"));
        responses.add(detail(10, "Changed but off shelf", "OFF_SHELF"));
        responses.add(detail(13, "Fresh description 13", "ON_SHELF"));
        Iterator<ToolExecutionResult> tools = successes(responses);
        AtomicInteger product11Calls = new AtomicInteger();
        AtomicInteger product12Calls = new AtomicInteger();
        CurrentProductIndex index = new CurrentProductIndex(request -> {
            if (request.name().equals("get_product_detail") && request.arguments().contains("11")
                    && product11Calls.incrementAndGet() > 1) {
                return error("detail lookup failed");
            }
            if (request.name().equals("get_product_detail") && request.arguments().contains("12")
                    && product12Calls.incrementAndGet() > 1) {
                return success("null");
            }
            return tools.next();
        });

        CurrentProductIndex.ProductSnapshot snapshot = index.refresh(Set.of(10L, 11L, 12L, 13L));

        assertTrue(snapshot.complete());
        assertEquals("Cached description 11", snapshot.evidenceByProductId().get(11L).description());
        assertTrue(index.verifyCitation(snapshot, 10L).isEmpty());
        assertTrue(index.verifyCitation(snapshot, 11L).isEmpty());
        assertTrue(index.verifyCitation(snapshot, 12L).isEmpty());
        ProductEvidence freshCitation = index.verifyCitation(snapshot, 13L).orElseThrow();
        assertEquals("Fresh description 13", freshCitation.description());
        assertNotEquals(snapshot.evidenceByProductId().get(13L).digest(), freshCitation.digest());
    }

    @Test
    void failedDetailDisablesAllCitationsForRefresh() {
        List<ToolExecutionResult> invalidDetails = List.of(
                error("detail lookup failed for product 21"),
                success("null"),
                success("{\"id\":21,\"name\":\"Missing description\",\"status\":\"ON_SHELF\"}"),
                success(detail(21, "Unknown status", "GARBAGE")));

        for (ToolExecutionResult invalidDetail : invalidDetails) {
            Iterator<ToolExecutionResult> tools = List.of(
                    success(page(1, 2, List.of(20L, 21L), Set.of())),
                    success(detail(20, "Current description 20", "ON_SHELF")),
                    invalidDetail).iterator();
            CurrentProductIndex index = new CurrentProductIndex(request -> tools.next());

            CurrentProductIndex.ProductSnapshot snapshot = index.refresh(Set.of(20L, 21L));

            assertFalse(snapshot.complete());
            assertEquals(Set.of(), snapshot.productIds());
            assertTrue(index.verifyCitation(snapshot, 20L).isEmpty());
            assertTrue(index.verifyCitation(snapshot, 21L).isEmpty());
        }
    }

    @Test
    void validNonShelfDetailsDropOnlyThoseProducts() {
        Iterator<ToolExecutionResult> tools = List.of(
                success(page(1, 3, List.of(30L, 31L, 32L), Set.of())),
                success(detail(30, "Current description 30", "OFF_SHELF")),
                success(detail(31, "Draft description 31", "DRAFT")),
                success(detail(32, "Current description 32", "ON_SHELF")),
                success(detail(32, "Fresh description 32", "ON_SHELF"))).iterator();
        CurrentProductIndex index = new CurrentProductIndex(request -> tools.next());

        CurrentProductIndex.ProductSnapshot snapshot = index.refresh(Set.of(30L, 31L, 32L));

        assertTrue(snapshot.complete());
        assertEquals(Set.of(32L), snapshot.productIds());
        assertTrue(index.verifyCitation(snapshot, 30L).isEmpty());
        assertTrue(index.verifyCitation(snapshot, 31L).isEmpty());
        ProductEvidence fresh = index.verifyCitation(snapshot, 32L).orElseThrow();
        assertEquals("PRODUCT-32", fresh.sourceId());
        assertEquals("Fresh description 32", fresh.description());
    }

    @Test
    void changedDescriptionRefreshesDigest() {
        List<String> responses = List.of(
                page(1, 1, List.of(42L), Set.of()),
                detail(42, "First description", "ON_SHELF"),
                page(1, 1, List.of(42L), Set.of()),
                detail(42, "Changed current description", "ON_SHELF"));
        Iterator<ToolExecutionResult> tools = successes(responses);
        CurrentProductIndex index = new CurrentProductIndex(request -> tools.next());

        CurrentProductIndex.ProductSnapshot before = index.refresh(Set.of(42L));
        CurrentProductIndex.ProductSnapshot after = index.refresh(Set.of(42L));

        ProductEvidence beforeEvidence = before.evidenceByProductId().get(42L);
        ProductEvidence afterEvidence = after.evidenceByProductId().get(42L);
        assertNotEquals(beforeEvidence.digest(), afterEvidence.digest());
        assertEquals("Changed current description", afterEvidence.description());
        assertEquals("PRODUCT-42", afterEvidence.sourceId());
        assertFalse(afterEvidence.skus().isEmpty());
    }

    private static String page(int current, long total, List<Long> ids, Set<Long> offShelfIds) {
        List<String> records = new ArrayList<>();
        for (Long id : ids) {
            String status = offShelfIds.contains(id) ? "OFF_SHELF" : "ON_SHELF";
            records.add("{\"id\":" + id + ",\"name\":\"Product " + id
                    + "\",\"description\":\"Listed description " + id
                    + "\",\"status\":\"" + status + "\"}");
        }
        return "{\"records\":[" + String.join(",", records) + "],\"total\":" + total
                + ",\"size\":20,\"current\":" + current + "}";
    }

    private static String detail(long id, String description, String status) {
        try {
            return MAPPER.writeValueAsString(MAPPER.readTree("""
                    {"id":%d,"name":"Current Product","description":"%s","status":"%s",
                     "skus":[{"id":%d,"specs":"blue / medium","price":12.50,"stock":4}]}
                    """.formatted(id, description, status, id + 1000)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Iterator<ToolExecutionResult> successes(List<String> jsonResults) {
        return jsonResults.stream().map(CurrentProductIndexTest::success).iterator();
    }

    private static ToolExecutionResult success(String text) {
        return ToolExecutionResult.builder().resultText(text).isError(false).build();
    }

    private static ToolExecutionResult error(String text) {
        return ToolExecutionResult.builder().resultText(text).isError(true).build();
    }
}
