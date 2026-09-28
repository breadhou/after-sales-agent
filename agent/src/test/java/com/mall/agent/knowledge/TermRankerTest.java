package com.mall.agent.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TermRankerTest {

    @Test
    void chineseBigramsAndLatinNumbersRankDeterministically() {
        TermRanker ranker = new TermRanker();
        Map<String, String> sources = Map.of(
                "FAQ-004", "无关内容",
                "FAQ-003", "SKU 123",
                "FAQ-005", "退款",
                "FAQ-002", "退款",
                "FAQ-001", "退款 SKU 123");

        assertEquals(java.util.List.of("FAQ-001", "FAQ-003", "FAQ-002", "FAQ-005"),
                ranker.rank("退款 SKU-123", sources));
        assertEquals(java.util.List.of("FAQ-001", "FAQ-003", "FAQ-002", "FAQ-005"),
                ranker.rank("退款 SKU-123", sources));
        assertEquals(java.util.List.of(), ranker.rank("完全没有命中", sources));
        assertEquals("FAQ-001", ranker.rank("退款", sources).get(0));
    }
}
