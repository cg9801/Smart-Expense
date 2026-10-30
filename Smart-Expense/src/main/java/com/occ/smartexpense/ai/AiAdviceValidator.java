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
import java.util.Map;
import java.util.Set;

/**
 * AI 建议输出校验，见 docs/11-AI识别与辅助审核.md §5.4。
 *
 * <p>纯函数：入参是模型原始输出 + 「证据源名 → JSON」映射，任何情况下都返回 {@link Verdict}、
 * 不向外抛异常——调用方只需判 {@code valid()}。
 *
 * <p>证据源 = 本轮真正调用过的工具（工具名 → 返回 JSON）+ 初始上下文里的 {@code hardFindings}。
 * 校验到<b>路径级</b>：{@code getReportDetail#items.0.amount} 必须能在对应 JSON 里真实走到。
 */
@Component
@RequiredArgsConstructor
public class AiAdviceValidator {

    private static final List<String> VALID_RISK = List.of("LOW", "MEDIUM", "HIGH");

    /** 初始上下文里自带的硬规则证据，没有对应工具 */
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

    /**
     * @param finalJson       模型给出的最终 JSON
     * @param evidenceSources 证据源：工具名 → 工具返回 JSON，外加 {@code hardFindings} → 硬规则 JSON
     */
    public Verdict validate(String finalJson, Map<String, String> evidenceSources) {
        JsonNode node;
        try {
            node = objectMapper.readTree(finalJson);
        } catch (Exception e) {
            return invalid("BAD_FINAL_JSON");
        }

        String riskLevel = node.path("riskLevel").asText("LOW").toUpperCase(Locale.ROOT);
        if (!VALID_RISK.contains(riskLevel)) {
            // 非法等级不能静默降为 LOW：那等于系统主动调低风险，违反 docs/11 §5.5
            return invalid("BAD_RISK_LEVEL");
        }

        BigDecimal confidence = BigDecimal.valueOf(node.path("confidence").asDouble(0.5))
                .setScale(3, RoundingMode.HALF_UP);
        if (confidence.compareTo(MIN_CONFIDENCE) < 0 || confidence.compareTo(MAX_CONFIDENCE) > 0) {
            return invalid("BAD_CONFIDENCE");
        }

        Set<String> sources = normalizedSources(evidenceSources);
        JsonNode reasons = node.path("reasons");
        ArrayNode kept = objectMapper.createArrayNode();
        if (reasons.isArray()) {
            for (JsonNode reason : reasons) {
                if (isEvidenceAllowed(reason, sources, evidenceSources)) {
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
     * 可用证据源名字集合（小写）：工具返回 + 硬规则。
     * 模型对 getBudget / getbudget 的大小写并不稳定，统一规范化。
     */
    private Set<String> normalizedSources(Map<String, String> evidenceSources) {
        Set<String> sources = new HashSet<>();
        if (evidenceSources != null) {
            for (String name : evidenceSources.keySet()) {
                if (name != null) {
                    sources.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        sources.add(HARD_FINDINGS);
        return sources;
    }

    /**
     * evidence 形如 {@code 来源名#字段路径}；没有 # 时整串当来源名、路径视为整个结果。
     * 两层校验：来源在白名单里，且路径能在该来源的 JSON 里走通。
     */
    private boolean isEvidenceAllowed(JsonNode reason, Set<String> sources, Map<String, String> evidenceSources) {
        String evidence = reason.path("evidence").asText("");
        if (evidence.isBlank()) {
            return false;
        }
        int index = evidence.indexOf('#');
        String sourceName = index >= 0 ? evidence.substring(0, index).trim() : evidence.trim();
        String path = index >= 0 ? evidence.substring(index + 1).trim() : "";

        String normalizedName = sourceName.toLowerCase(Locale.ROOT);
        if (!sources.contains(normalizedName)) {
            return false;
        }
        if (path.isEmpty()) {
            return true;   // 只引用整份结果，路径无从校验
        }

        String json = lookup(evidenceSources, normalizedName);
        if (json == null) {
            return false;
        }
        try {
            return resolve(objectMapper.readTree(json), path);
        } catch (Exception e) {
            return false;   // JSON 坏了或路径非法，一律视为幻觉
        }
    }

    /** 按原名（大小写不敏感）从映射取 JSON */
    private String lookup(Map<String, String> evidenceSources, String normalizedName) {
        if (evidenceSources == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : evidenceSources.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toLowerCase(Locale.ROOT).equals(normalizedName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 按 {@code a.b.0.c} 逐段走 JSON 树：
     * 数组段必须是合法下标，对象段必须是存在的字段；任何一步走不通返回 false。
     */
    private boolean resolve(JsonNode node, String path) {
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            if (segment.isEmpty()) {
                return false;
            }
            if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (index < 0 || index >= current.size()) {
                    return false;
                }
                current = current.get(index);
            } else if (current.isObject()) {
                current = current.get(segment);
            } else {
                return false;
            }
            if (current == null || current.isMissingNode()) {
                return false;
            }
        }
        return true;
    }

    private Verdict invalid(String errorCode) {
        return new Verdict(null, null, null, errorCode);
    }
}