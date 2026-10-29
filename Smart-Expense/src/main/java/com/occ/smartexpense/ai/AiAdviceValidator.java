package com.occ.smartexpense.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 建议输出校验，见 docs/11-AI识别与辅助审核.md §5.4。
 *
 * <p>纯函数：入参是模型原始输出 + 本轮真正执行过的工具名，任何情况下都返回 {@link Verdict}、
 * 不向外抛异常——调用方只需判 {@code valid()}。
 *
 * <p>第一版只校验 evidence 里的<b>工具名</b>白名单；路径级校验
 * （{@code getReportDetail#item.1.category} 这种）尚未实现，见 docs/11 §5.4。
 */
@Component
@RequiredArgsConstructor
public class AiAdviceValidator {

    private static final List<String> VALID_RISK = List.of("LOW", "MEDIUM", "HIGH");

    /** 初始上下文里自带的硬规则证据，无需工具调用即可引用 */
    private static final String HARD_FINDINGS = "hardfindings";

    /** ai_review.confidence 是 DECIMAL(4,3)，超界会在入库时报 Out of range，先在内存里拦住 */
    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

    private final ObjectMapper objectMapper;

    /** 校验结论：合格时带清洗后的字段，不合格时只带 errorCode */
    public record Verdict(String riskLevel, BigDecimal confidence,
                          String reasonsJson, String errorCode) {
        public boolean valid() {
            return errorCode == null;
        }
    }

    public Verdict validate(String finalJson, Set<String> usedTools) {
        JsonNode node;
        try {
            node = objectMapper.readTree(finalJson);
        } catch (Exception e) {
            return invalid("BAD_FINAL_JSON");
        }

        String riskLevel = node.path("riskLevel").asText("LOW").toUpperCase(Locale.ROOT);
        if (!VALID_RISK.contains(riskLevel)) {
            // 非法等级不能静默降为 LOW：那等于系统主动调低风险
            return invalid("BAD_RISK_LEVEL");
        }

        BigDecimal confidence = BigDecimal.valueOf(node.path("confidence").asDouble(0.5))
                .setScale(3, RoundingMode.HALF_UP);
        if (confidence.compareTo(MIN_CONFIDENCE) < 0 || confidence.compareTo(MAX_CONFIDENCE) > 0) {
            return invalid("BAD_CONFIDENCE");
        }

        Set<String> allowed = allowedTools(usedTools);
        JsonNode reasons = node.path("reasons");
        ArrayNode kept = objectMapper.createArrayNode();
        if (reasons.isArray()) {
            for (JsonNode reason : reasons) {
                if (isEvidenceAllowed(reason, allowed)) {
                    kept.add(reason);
                }
            }
        }

        // 给了风险等级却一条理由都拿不出来，这条建议没有价值，宁可不展示
        if (kept.isEmpty() && !"LOW".equals(riskLevel)) {
            return invalid("NO_EVIDENCE");
        }
        return new Verdict(riskLevel, confidence, kept.toString(), null);
    }

    /**
     * 白名单副本：usedTools 是 Set.copyOf 出来的不可变集合，不能直接 add；
     * 同时做大小写规范化——模型对 getBudget / getbudget 并不稳定。
     */
    private Set<String> allowedTools(Set<String> usedTools) {
        Set<String> allowed = usedTools.stream()
                .map(tool -> tool.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        allowed.add(HARD_FINDINGS);
        return allowed;
    }

    /** evidence 形如 {@code 工具名#字段路径}；没有 # 时整串当工具名（模型可能只说"依据 getBudget"） */
    private boolean isEvidenceAllowed(JsonNode reason, Set<String> allowed) {
        String evidence = reason.path("evidence").asText("");
        if (evidence.isBlank()) {
            return false;
        }
        int index = evidence.indexOf('#');
        String tool = index >= 0 ? evidence.substring(0, index) : evidence;
        return allowed.contains(tool.trim().toLowerCase(Locale.ROOT));
    }

    private Verdict invalid(String errorCode) {
        return new Verdict(null, null, null, errorCode);
    }
}