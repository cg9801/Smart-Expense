package com.occ.smartexpense.rule;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 风险等级合并：最终等级 = max(硬规则, AI)，**只能升不能降**（docs/11 §5.5）。
 *
 * <p>纯函数：不做 IO。硬规则命中 HIGH 时，AI 说 LOW 也一律以 HIGH 为准——
 * AI 只能提供硬规则看不出来的语义嫌疑，不能替系统降压。
 */
@Component
public class RiskMerger {

    /**
     * @param findings 硬规则命中（可为空，表示没有硬规则命中）
     * @param aiRisk   AI 给出的等级（LOW/MEDIUM/HIGH；非法值按 LOW 处理，正常不会出现）
     * @return 合并后的等级
     */
    public String merge(List<HardFinding> findings, String aiRisk) {
        Severity ai = parse(aiRisk);
        Severity hard = highest(findings);
        if (hard == null) {
            return ai.name();
        }
        return hard.atLeast(ai) ? hard.name() : ai.name();
    }

    /** 取硬规则里的最高等级；没有命中返回 null */
    private Severity highest(List<HardFinding> findings) {
        if (findings == null) {
            return null;
        }
        Severity highest = null;
        for (HardFinding finding : findings) {
            if (finding == null || finding.severity() == null) {
                continue;
            }
            if (highest == null || finding.severity().atLeast(highest)) {
                highest = finding.severity();
            }
        }
        return highest;
    }

    private Severity parse(String risk) {
        if (risk == null) {
            return Severity.LOW;
        }
        try {
            return Severity.valueOf(risk.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Severity.LOW;
        }
    }
}