package com.mall.agent.knowledge;

import java.math.BigDecimal;
import java.util.List;

/** Fresh, citation-ready facts read from one current product detail response. */
public record ProductEvidence(String sourceId, Long productId, String digest,
                              String name, String description, List<SkuFact> skus) {

    public ProductEvidence {
        if (sourceId == null || productId == null || productId <= 0
                || !sourceId.equals("PRODUCT-" + productId)
                || digest == null || digest.isBlank() || name == null || name.isBlank()
                || description == null || description.isBlank() || skus == null) {
            throw new IllegalArgumentException("商品证据无效");
        }
        skus = List.copyOf(skus);
    }

    /** Current SKU attributes exposed by the read-only product detail MCP tool. */
    public record SkuFact(Long skuId, String specs, BigDecimal price, Long stock) {
        public SkuFact {
            if (skuId == null || skuId <= 0 || specs == null || specs.isBlank()
                    || price == null || price.signum() < 0 || stock == null || stock < 0) {
                throw new IllegalArgumentException("商品 SKU 证据无效");
            }
        }
    }
}
