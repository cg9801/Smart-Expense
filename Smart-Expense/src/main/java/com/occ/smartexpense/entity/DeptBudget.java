package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 部门月度预算 dept_budget，字段见 sql/schema.sql §2 与 docs/01-表结构.md §4.3。
 * 可用额度 = total_amount - used_amount - frozen_amount，见 docs/06-预算冻结与扣减.md §2.1。
 */
@Data
@TableName("dept_budget")
public class DeptBudget {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deptId;

    /** 财年，如 2026 */
    private Short fiscalYear;

    /** 账期 YYYY-MM，如 2026-10 */
    private String period;

    private BigDecimal totalAmount;

    /** 已实扣 */
    private BigDecimal usedAmount;

    /** 在途冻结 */
    private BigDecimal frozenAmount;

    /** 乐观锁字段，本项目不启用（统一用条件更新） */
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
