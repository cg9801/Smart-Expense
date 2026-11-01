package com.occ.smartexpense.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批轨迹出参（GET /reports/{id}/timeline），对应 docs/02 §8 审计日志回放。
 * detail 保留原始 JSON 字符串，前端按需解析（与 ExpenseStatusLog.detail 一致）。
 */
@Data
public class TimelineVO {

    private Long id;

    private String traceId;

    private String action;

    private String fromStatus;

    private String toStatus;

    /** NULL 表示系统动作 */
    private Long operatorId;

    private String operatorName;

    private String detail;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
