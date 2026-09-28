package com.mall.agent.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Builds a request-scoped index of complete, current, allowlisted on-shelf product records. */
public final class CurrentProductIndex {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 100;
    private static final Set<String> PRODUCT_STATUSES = Set.of("DRAFT", "ON_SHELF", "OFF_SHELF");

    private final Function<ToolExecutionRequest, ToolExecutionResult> toolCaller;

    public CurrentProductIndex(McpClient mcp) {
        this(mcp::executeTool);
    }

    /** Test seam for the two read-only MCP calls; production callers use {@link McpClient}. */
    CurrentProductIndex(Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        this.toolCaller = Objects.requireNonNull(toolCaller, "toolCaller");
    }

    /**
     * Reads every page in one bounded traversal, then loads detail for matching on-shelf IDs.
     * A malformed or incomplete list disables citations for this snapshot; it never falls back
     * to a previous generation.
     */
    public ProductSnapshot refresh(Set<Long> allowedIds) {
        Set<Long> allowlist = validatedAllowlist(allowedIds);
        if (allowlist.isEmpty()) {
            return ProductSnapshot.disabled(allowlist);
        }

        final List<ProductRow> rows;
        final PageSummary summary;
        try {
            Traversal traversal = readCompleteTraversal();
            rows = traversal.rows();
            summary = traversal.summary();
        } catch (RuntimeException e) {
            return ProductSnapshot.disabled(allowlist);
        }

        Map<Long, ProductEvidence> evidenceById = new LinkedHashMap<>();
        List<ProductRow> candidates = rows.stream()
                .filter(row -> "ON_SHELF".equals(row.status()) && allowlist.contains(row.id()))
                .sorted((left, right) -> Long.compare(left.id(), right.id()))
                .toList();
        for (ProductRow row : candidates) {
            ProductDetail detail = readProductDetail(row.id());
            if (!detail.valid()) {
                return ProductSnapshot.disabled(allowlist);
            }
            if (detail.evidence() != null) {
                evidenceById.put(row.id(), detail.evidence());
            }
        }

        return ProductSnapshot.complete(allowlist, evidenceById,
                summary.total(), summary.current(), PAGE_SIZE);
    }

    /** Re-reads detail immediately before citation and returns only current on-shelf evidence. */
    public Optional<ProductEvidence> verifyCitation(ProductSnapshot snapshot, Long productId) {
        if (snapshot == null || !snapshot.complete() || productId == null || productId <= 0
                || !snapshot.allowedIds().contains(productId)
                || !snapshot.productIds().contains(productId)) {
            return Optional.empty();
        }
        ProductDetail detail = readProductDetail(productId);
        return detail.valid() ? Optional.ofNullable(detail.evidence()) : Optional.empty();
    }

    private Traversal readCompleteTraversal() {
        List<ProductRow> rows = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        long expectedTotal = -1;
        int pageCount = -1;

        for (int pageNum = 1; pageNum <= (pageCount < 0 ? 1 : pageCount); pageNum++) {
            JsonNode page = invoke("list_on_shelf_products",
                    "{\"pageNum\":" + pageNum + ",\"pageSize\":" + PAGE_SIZE + "}");
            if (page == null || !page.isObject() || !page.path("records").isArray()
                    || !integral(page.get("total")) || !integral(page.get("size"))
                    || !integral(page.get("current"))) {
                throw new ProductIndexException("商品列表页结构不完整");
            }

            long total = page.path("total").longValue();
            long size = page.path("size").longValue();
            long current = page.path("current").longValue();
            if (total < 0 || size != PAGE_SIZE || current != pageNum
                    || (expectedTotal >= 0 && total != expectedTotal)) {
                throw new ProductIndexException("商品列表分页元数据不一致");
            }
            if (expectedTotal < 0) {
                expectedTotal = total;
                long requiredPages = Math.max(1, total / PAGE_SIZE + (total % PAGE_SIZE == 0 ? 0 : 1));
                if (requiredPages > MAX_PAGES) {
                    throw new ProductIndexException("商品列表超过分页上限");
                }
                pageCount = (int) requiredPages;
            }

            long recordsBeforePage = (long) (pageNum - 1) * PAGE_SIZE;
            long remaining = Math.max(0, expectedTotal - recordsBeforePage);
            int expectedRecords = (int) Math.min(PAGE_SIZE, remaining);
            JsonNode records = page.path("records");
            if (records.size() != expectedRecords) {
                throw new ProductIndexException("商品列表分页记录数不完整");
            }

            for (JsonNode record : records) {
                ProductRow row = parseListRecord(record);
                if (!seenIds.add(row.id())) {
                    throw new ProductIndexException("商品列表含重复商品 ID");
                }
                rows.add(row);
            }
        }

        if (expectedTotal != rows.size()) {
            throw new ProductIndexException("商品列表总数与记录数不一致");
        }
        return new Traversal(List.copyOf(rows), new PageSummary(expectedTotal, pageCount));
    }

    private ProductRow parseListRecord(JsonNode record) {
        if (record == null || !record.isObject() || !integral(record.get("id"))
                || record.path("id").longValue() <= 0
                || !nonblankText(record.get("name"))
                || !nonblankText(record.get("description"))
                || !nonblankText(record.get("status"))
                || !PRODUCT_STATUSES.contains(record.path("status").textValue())) {
            throw new ProductIndexException("商品列表含格式不完整的记录");
        }
        return new ProductRow(record.path("id").longValue(), record.path("status").textValue());
    }

    private ProductDetail readProductDetail(long productId) {
        try {
            JsonNode detail = invoke("get_product_detail", "{\"productId\":" + productId + "}");
            String status = detail != null && detail.isObject()
                    ? textValue(detail.get("status")) : null;
            if (detail == null || !detail.isObject() || !integral(detail.get("id"))
                    || detail.path("id").longValue() != productId
                    || status == null || !PRODUCT_STATUSES.contains(status)
                    || !nonblankText(detail.get("name"))
                    || !nonblankText(detail.get("description"))) {
                return ProductDetail.invalid();
            }

            List<ProductEvidence.SkuFact> skus = parseSkus(detail.get("skus"));
            String name = detail.path("name").textValue();
            String description = detail.path("description").textValue();
            if (!"ON_SHELF".equals(status)) {
                return ProductDetail.validWithoutCitation();
            }
            ProductEvidence evidence = new ProductEvidence("PRODUCT-" + productId, productId,
                    digest(productId, name, description, skus), name, description, skus);
            return ProductDetail.valid(evidence);
        } catch (RuntimeException e) {
            return ProductDetail.invalid();
        }
    }

    private static List<ProductEvidence.SkuFact> parseSkus(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new ProductIndexException("商品详情缺少 SKU 信息");
        }
        List<ProductEvidence.SkuFact> skus = new ArrayList<>();
        Set<Long> seenSkuIds = new HashSet<>();
        for (JsonNode sku : node) {
            if (sku == null || !sku.isObject() || !integral(sku.get("id"))
                    || sku.path("id").longValue() <= 0 || !nonblankText(sku.get("specs"))
                    || !numeric(sku.get("price")) || !integral(sku.get("stock"))
                    || sku.path("stock").longValue() < 0) {
                throw new ProductIndexException("商品详情含格式不完整的 SKU");
            }
            if (!seenSkuIds.add(sku.path("id").longValue())) {
                throw new ProductIndexException("商品详情含重复 SKU ID");
            }
            BigDecimal price = sku.path("price").decimalValue();
            if (price.signum() < 0) {
                throw new ProductIndexException("商品 SKU 价格无效");
            }
            skus.add(new ProductEvidence.SkuFact(sku.path("id").longValue(),
                    sku.path("specs").textValue(), price, sku.path("stock").longValue()));
        }
        return List.copyOf(skus);
    }

    private JsonNode invoke(String toolName, String arguments) {
        ToolExecutionResult result;
        try {
            result = toolCaller.apply(ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(arguments)
                    .build());
        } catch (RuntimeException e) {
            throw new ProductIndexException("商品资料读取失败", e);
        }
        if (result == null || result.isError()) {
            throw new ProductIndexException("商品资料读取失败");
        }
        final String json;
        try {
            json = result.resultText();
        } catch (RuntimeException e) {
            throw new ProductIndexException("商品资料响应无效", e);
        }
        if (json == null || json.isBlank()) {
            throw new ProductIndexException("商品资料响应为空");
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ProductIndexException("商品资料不是有效 JSON", e);
        }
    }

    private static String digest(long productId, String name, String description,
                                 List<ProductEvidence.SkuFact> skus) {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("productId", productId);
        content.put("name", name);
        content.put("description", description);
        ArrayNode skuNodes = content.putArray("skus");
        for (ProductEvidence.SkuFact sku : skus) {
            ObjectNode item = skuNodes.addObject();
            item.put("id", sku.skuId());
            item.put("specs", sku.specs());
            item.put("price", sku.price().toPlainString());
            item.put("stock", sku.stock());
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Set<Long> validatedAllowlist(Set<Long> allowedIds) {
        if (allowedIds == null || allowedIds.isEmpty()
                || allowedIds.stream().anyMatch(id -> id == null || id <= 0)) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(allowedIds));
    }

    private static boolean integral(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }

    private static boolean numeric(JsonNode node) {
        return node != null && node.isNumber();
    }

    private static boolean nonblankText(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank();
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private record ProductRow(long id, String status) {
    }

    private record ProductDetail(boolean valid, ProductEvidence evidence) {
        private static ProductDetail valid(ProductEvidence evidence) {
            return new ProductDetail(true, evidence);
        }

        private static ProductDetail validWithoutCitation() {
            return new ProductDetail(true, null);
        }

        private static ProductDetail invalid() {
            return new ProductDetail(false, null);
        }
    }

    private record PageSummary(long total, int current) {
    }

    private record Traversal(List<ProductRow> rows, PageSummary summary) {
    }

    /** Immutable, request-pinned generation. Incomplete traversal always contains no citations. */
    public record ProductSnapshot(boolean complete, Set<Long> allowedIds, Set<Long> productIds,
                                  Map<Long, ProductEvidence> evidenceByProductId,
                                  long total, int current, int size) {

        public ProductSnapshot {
            allowedIds = immutableSet(allowedIds);
            productIds = immutableSet(productIds);
            evidenceByProductId = Collections.unmodifiableMap(new LinkedHashMap<>(evidenceByProductId));
            if (!productIds.equals(evidenceByProductId.keySet())
                    || !allowedIds.containsAll(productIds)
                    || evidenceByProductId.entrySet().stream().anyMatch(entry ->
                    !entry.getKey().equals(entry.getValue().productId()))
                    || allowedIds.stream().anyMatch(id -> id == null || id <= 0)
                    || (complete && (total < 0 || current <= 0 || size != PAGE_SIZE))
                    || (!complete && (!productIds.isEmpty() || total != -1 || current != 0 || size != 0))) {
                throw new IllegalArgumentException("商品快照无效");
            }
        }

        private static ProductSnapshot complete(Set<Long> allowedIds,
                                                Map<Long, ProductEvidence> evidenceById,
                                                long total, int current, int size) {
            Set<Long> productIds = new LinkedHashSet<>(evidenceById.keySet());
            return new ProductSnapshot(true, allowedIds, productIds, evidenceById,
                    total, current, size);
        }

        private static ProductSnapshot disabled(Set<Long> allowedIds) {
            return new ProductSnapshot(false, allowedIds, Set.of(), Map.of(), -1, 0, 0);
        }

        private static Set<Long> immutableSet(Set<Long> source) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(source));
        }
    }

    private static final class ProductIndexException extends RuntimeException {
        private ProductIndexException(String message) {
            super(message);
        }

        private ProductIndexException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
