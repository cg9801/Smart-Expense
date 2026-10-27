package com.occ.smartexpense.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审批详情里的 AI 建议，见 docs/11 §5.7。
 * 只读展示，不参与状态流转。
 */
@Data
public class AiAdviceVO {

    private Long id;

    private Long reportId;

    private Long reportVersion;

    /** LOW / MEDIUM / HIGH */
    private String riskLevel;

    private BigDecimal confidence;

    /** 模型 reasons（JSON 数组字符串，前端直接渲染） */
    private String reasons;

    /** 硬规则命中（JSON 数组字符串） */
    private String hardFindings;

    private String model;

    /** SUCCESS / FAILED / SKIPPED / STALE */
    private String status;

    /** 建议是否已过期（report.version 变了） */
    private boolean stale;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
