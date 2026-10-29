package com.occ.smartexpense.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

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

    @Test
    void evidenceOfToolNeverCalled_isDropped() {
        String finalJson = """
                {"riskLevel":"LOW","confidence":0.9,
                 "reasons":[{"code":"MOCK","text":"模型编的理由","evidence":"dropTable#x"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, Set.of("getBudget"));

        assertTrue(verdict.valid());
        assertEquals("[]", verdict.reasonsJson(), "没调用过的工具名必须被剔除");
    }

    @Test
    void allReasonsDroppedWithNonLowRisk_isInvalid() {
        String finalJson = """
                {"riskLevel":"HIGH","confidence":0.9,
                 "reasons":[{"code":"MOCK","text":"编的","evidence":"dropTable#x"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, Set.of());

        assertFalse(verdict.valid());
        assertEquals("NO_EVIDENCE", verdict.errorCode(), "高风险却拿不出理由，整条必须丢弃");
    }

    @Test
    void allReasonsDroppedWithLowRisk_isStillValid() {
        // LOW + 空理由合法：低风险本来就可以没有理由（这正是规则边界）
        String finalJson = """
                {"riskLevel":"LOW","confidence":0.8,"reasons":[]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, Set.of());

        assertTrue(verdict.valid());
        assertEquals("LOW", verdict.riskLevel());
    }

    @Test
    void evidenceToolNameIsCaseInsensitive() {
        String finalJson = """
                {"riskLevel":"MEDIUM","confidence":0.8,
                 "reasons":[{"code":"BUDGET","text":"预算紧张","evidence":"GetBudget#available"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, Set.of("getBudget"));

        assertTrue(verdict.valid());
        assertTrue(verdict.reasonsJson().contains("GetBudget#available"), "大小写不同不该误杀");
    }

    @Test
    void hardFindingsEvidence_isAllowedWithoutToolCall() {
        String finalJson = """
                {"riskLevel":"HIGH","confidence":0.8,
                 "reasons":[{"code":"DUP","text":"疑似重复报销","evidence":"hardFindings#0"}]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, Set.of());

        assertTrue(verdict.valid(), "hardFindings 来自上下文，不需要工具调用");
    }

    @Test
    void confidenceOutOfRange_isInvalidBeforeInsert() {
        // 模型把百分比当小数：95 会撞 DECIMAL(4,3) 的 Out of range
        String finalJson = """
                {"riskLevel":"LOW","confidence":95,"reasons":[]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, Set.of());

        assertFalse(verdict.valid());
        assertEquals("BAD_CONFIDENCE", verdict.errorCode());
    }

    @Test
    void illegalRiskLevel_isInvalidNotSilentlyLow() {
        String finalJson = """
                {"riskLevel":"CRITICAL","confidence":0.9,"reasons":[]}
                """;

        AiAdviceValidator.Verdict verdict = validator.validate(finalJson, Set.of());

        assertFalse(verdict.valid());
        assertEquals("BAD_RISK_LEVEL", verdict.errorCode(), "不能静默降级为 LOW");
    }

    @Test
    void brokenJson_isInvalidNotException() {
        AiAdviceValidator.Verdict verdict = validator.validate("not-a-json", Set.of());

        assertFalse(verdict.valid());
        assertEquals("BAD_FINAL_JSON", verdict.errorCode());
    }
}