package com.occ.smartexpense.rule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 风险合并单元测试，见 docs/11-AI识别与辅助审核.md §5.5。
 * 铁律：最终等级 = max(硬规则, AI)，只能升不能降。
 */
class RiskMergerTest {

    private final RiskMerger merger = new RiskMerger();

    private HardFinding high() {
        return new HardFinding(HardRuleEngine.BUDGET_EXCEEDED, Severity.HIGH, "超预算");
    }

    private HardFinding low() {
        return new HardFinding(HardRuleEngine.MISSING_REQUIRED_FIELD, Severity.LOW, "缺字段");
    }

    @Test
    void hardHigh_beatsAiLow() {
        assertEquals("HIGH", merger.merge(List.of(high()), "LOW"));
    }

    @Test
    void aiHigh_beatsHardLow() {
        assertEquals("HIGH", merger.merge(List.of(low()), "HIGH"));
    }

    @Test
    void noFindings_usesAiLevel() {
        assertEquals("MEDIUM", merger.merge(List.of(), "MEDIUM"));
        assertEquals("MEDIUM", merger.merge(null, "MEDIUM"));
    }

    @Test
    void picksHighestAmongFindings() {
        assertEquals("HIGH", merger.merge(List.of(low(), high()), "LOW"));
    }

    @Test
    void illegalAiRisk_fallsBackToLow() {
        assertEquals("LOW", merger.merge(List.of(), "CRITICAL"));
    }
}