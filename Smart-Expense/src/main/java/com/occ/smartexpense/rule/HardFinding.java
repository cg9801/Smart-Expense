package com.occ.smartexpense.rule;

/**
 * 硬规则命中结果，见 docs/11-AI识别与辅助审核.md §2.1。
 *
 * <p>不可变值对象：一条命中一个对象，引擎按规则顺序产出，不做去重与排序。
 * 落库时序列化成 JSON 数组，存进 {@code ai_review.hard_findings}。
 */
public record HardFinding(String code, Severity severity, String message) {
}