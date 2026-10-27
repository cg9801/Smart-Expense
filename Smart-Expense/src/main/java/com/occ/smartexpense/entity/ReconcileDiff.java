package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账差异 reconcile_diff，字段见 sql/schema.sql §7 与 docs/08 §3。
 * uk_date_type_report：同一单同类差异每天只留一条，重复对账幂等。
 */
@Data
@TableName("reconcile_diff")
public class ReconcileDiff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate bizDate;

    /** BUDGET_NOT_DEDUCTED / PAYMENT_MISSING / AMOUNT_MISMATCH / STATUS_STUCK */
    private String diffType;

    private Long reportId;

    /** 差异上下文（JSON 字符串，修复失败时追加 repairError） */
    private String detail;

    /** OPEN / REPAIRED / IGNORED */
    private String status;

    private LocalDateTime repairedAt;

    /** NULL 表示自动补偿；人工修复记操作人 */
    private Long handlerId;

    private LocalDateTime createdAt;
}
