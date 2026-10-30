package com.occ.smartexpense.rule;

/**
 * 硬规则严重性，见 docs/11-AI识别与辅助审核.md §2.1 与 §5.5。
 *
 * <p>词表与 {@code ai_review.risk_level} 完全一致（LOW/MEDIUM/HIGH），
 * 因此风险合并 {@code max(硬规则, AI)} 可以直接比较 {@link #ordinal()}，不需要映射表。
 *
 * <p><b>声明顺序即严重性顺序</b>：不要为了字母序重排常量，否则 {@link #atLeast} 会静默算错。
 */
public enum Severity {

    LOW, MEDIUM, HIGH;

    /** 本级是否不低于 {@code other}，供「风险只能升不能降」的合并使用 */
    public boolean atLeast(Severity other) {
        return ordinal() >= other.ordinal();
    }
}