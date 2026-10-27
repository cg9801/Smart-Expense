package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报销单主表 expense_report，字段定义见 docs/01-表结构.md §4.1。
 * 状态取值见 docs/02-状态机.md §1。
 */
@Data
@TableName("expense_report")
public class ExpenseReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reportNo;

    /** 提交幂等 token，草稿为 null（唯一索引允许多 null） */
    private String submitToken;

    private Long applicantId;

    /** 提交时部门快照 */
    private Long applicantDeptId;

    private String title;

    private String expenseType;

    private BigDecimal amountTotal;

    private String currency;

    private String status;

    private Integer currentStep;

    private Integer totalSteps;

    private Long budgetId;

    private String budgetPeriod;

    /** 乐观锁版本号 */
    private Integer version;

    private LocalDateTime submittedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime paidAt;

    private String remark;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
