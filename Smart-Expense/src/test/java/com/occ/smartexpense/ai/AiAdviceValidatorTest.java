package com.occ.smartexpense.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出校验层单元测试：不启 Spring、不连库（docs/11 §5.4）。
 *
 * <p>纯函数的价值就在这里：每个规则 5 毫秒验完，不用起应用、不用造消息。
 */
class AiAdviceValidatorTest {

    private final AiAdviceValidator validator = new AiAdviceValidator(new ObjectMapper());

    private static final String BUDGET_JSON =
            "{\"total\":\"50000.00\",\"used\":\"3000.00\",\"available\":\"46500.00\"}";

    private static final String DETAIL_JSON =
            "{\"reportId\":1001,\"items\":[{\"lineNo\":1,\"amount\":\"1500.00\"}]}";

    private Map<String, String> sources() {
        return Map.of("getBudget", BUDGET_JSON, "getReportDetail", DETAIL_JSON);
    }

    // ---------- 工具名白名单 ----------

    @Test
    void evidenceOfToolNeverCalled_isDropped() {
        String finalJson = """
                {"riskLevel":"LOW","confidence":0.9,
                 "reasons":[{"code":"MOCK","text":"模型编的理由","evidence":"dropTable#x"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertTrue(verdict.valid());
        assertEquals("[]", verdict.reasonsJson(), "没调用过的工具名必须被剔除");
    }

    @Test
    void allReasonsDroppedWithNonLowRisk_isInvalid() {
        String finalJson = """
                {"riskLevel":"HIGH","confidence":0.9,
                 "reasons":[{"code":"MOCK","text":"编的","evidence":"dropTable#x"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertFalse(verdict.valid());
        assertEquals("NO_EVIDENCE", verdict.errorCode());
    }

    @Test
    void allReasonsDroppedWithLowRisk_isStillValid() {
        String finalJson = """
                {"riskLevel":"LOW","confidence":0.8,"reasons":[]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertTrue(verdict.valid());
        assertEquals("LOW", verdict.riskLevel());
    }

    @Test
    void evidenceToolNameIsCaseInsensitive() {
        String finalJson = """
                {"riskLevel":"MEDIUM","confidence":0.8,
                 "reasons":[{"code":"BUDGET","text":"预算紧张","evidence":"GetBudget#available"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertTrue(verdict.valid());
        assertTrue(verdict.reasonsJson().contains("GetBudget#available"), "大小写不同不该误杀");
    }

    // ---------- 路径级校验 ----------

    @Test
    void existingPath_isKept() {
        String finalJson = """
                {"riskLevel":"MEDIUM","confidence":0.8,
                 "reasons":[{"code":"BUDGET","text":"可用额度偏低","evidence":"getBudget#available"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertTrue(verdict.valid());
        assertTrue(verdict.reasonsJson().contains("getBudget#available"));
    }

    @Test
    void missingFieldPath_isDropped() {
        String finalJson = """
                {"riskLevel":"LOW","confidence":0.8,
                 "reasons":[{"code":"BUDGET","text":"编的字段","evidence":"getBudget#notThere"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertEquals("[]", verdict.reasonsJson(), "字段不存在即幻觉");
    }

    @Test
    void arrayIndexPath_isKept() {
        String finalJson = """
                {"riskLevel":"MEDIUM","confidence":0.8,
                 "reasons":[{"code":"ITEM","text":"首行金额","evidence":"getReportDetail#items.0.amount"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertTrue(verdict.valid());
        assertTrue(verdict.reasonsJson().contains("items.0.amount"));
    }

    @Test
    void arrayIndexOutOfBounds_isDropped() {
        String finalJson = """
                {"riskLevel":"LOW","confidence":0.8,
                 "reasons":[{"code":"ITEM","text":"越界","evidence":"getReportDetail#items.5.amount"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertEquals("[]", verdict.reasonsJson(), "越界下标即幻觉");
    }

    @Test
    void hardFindingsEvidence_isAllowedWithoutToolCall() {
        String finalJson = """
                {"riskLevel":"HIGH","confidence":0.8,
                 "reasons":[{"code":"DUP","text":"疑似重复报销","evidence":"hardFindings#0"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson,
                Map.of("hardFindings", "[{\"code\":\"AMOUNT_MISMATCH\",\"severity\":\"HIGH\"}]"));

        assertTrue(verdict.valid(), "硬规则来自上下文，不需要工具调用");
    }

    @Test
    void hardFindingsIndexOutOfBounds_isDropped() {
        String finalJson = """
                {"riskLevel":"LOW","confidence":0.8,
                 "reasons":[{"code":"DUP","text":"编的","evidence":"hardFindings#3"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson,
                Map.of("hardFindings", "[{\"code\":\"AMOUNT_MISMATCH\"}]"));

        assertEquals("[]", verdict.reasonsJson());
    }

    // ---------- 其他校验 ----------

    @Test
    void confidenceOutOfRange_isInvalidBeforeInsert() {
        String finalJson = """
                {"riskLevel":"LOW","confidence":95,"reasons":[]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertFalse(verdict.valid());
        assertEquals("BAD_CONFIDENCE", verdict.errorCode());
    }

    @Test
    void illegalRiskLevel_isInvalidNotSilentlyLow() {
        String finalJson = """
                {"riskLevel":"CRITICAL","confidence":0.9,"reasons":[]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, sources());

        assertFalse(verdict.valid());
        assertEquals("BAD_RISK_LEVEL", verdict.errorCode(), "不能静默降级为 LOW");
    }

    @Test
    void brokenJson_isInvalidNotException() {
        AiAdviceValidator.Verdict verdict = validator.validate("not-a-json", sources());

        assertFalse(verdict.valid());
        assertEquals("BAD_FINAL_JSON", verdict.errorCode());
    }
}