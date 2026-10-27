package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 状态流转审计日志 expense_status_log，只追加不修改（docs/02-状态机.md §8）。
 * detail 列在库中是 JSON 类型，这里用 String 原样承载，避免引入额外的 JSON 处理器依赖。
 */
@Data
@TableName("expense_status_log")
public class ExpenseStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全链路 traceId，请求未带时由服务端生成 */
    private String traceId;

    private Long reportId;

    /** SUBMIT / WITHDRAW / APPROVE / REJECT / TRANSFER / PAY / RECONCILE */
    private String action;

    private String fromStatus;

    private String toStatus;

    /** NULL 表示系统动作 */
    private Long operatorId;

    private String operatorName;

    /** 动作上下文 JSON 字符串，如审批链摘要 */
    private String detail;

    private LocalDateTime createdAt;
}
