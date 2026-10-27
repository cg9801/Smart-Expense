package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 审批建议 ai_review，字段见 docs/11-AI识别与辅助审核.md §6.1。
 * 只写本表，不碰业务表；uk_report_version 保证同一版本只算一次。
 */
@Data
@TableName("ai_review")
public class AiReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    /** 计算时的 expense_report.version，用于过期判定 */
    private Long reportVersion;

    /** LOW / MEDIUM / HIGH（= max(硬规则, AI)） */
    private String riskLevel;

    private BigDecimal confidence;

    /** 模型 reasons（已过 evidence 白名单），JSON 字符串 */
    private String aiReasons;

    /** 硬规则命中，JSON 字符串 */
    private String hardFindings;

    private String model;

    private String promptVersion;

    private Integer latencyMs;

    /** Agent 循环的模型请求次数（<= 5） */
    private Integer agentSteps;

    private Integer toolCalls;

    private Integer toolErrors;

    /** SUCCESS / FAILED / SKIPPED / STALE */
    private String status;

    /** 1 采纳 / 0 忽略 / NULL 未表态 */
    private Integer adopted;

    private String errorMsg;

    private LocalDateTime createdAt;
}
