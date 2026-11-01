package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 公司总预算池 budget_pool（按月），字段见 sql/schema.sql §2。
 * 部门分配之和不得超过它；未设置时视为「不限制」（兼容旧数据）。
 */
@Data
@TableName("budget_pool")
public class BudgetPool {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** YYYY-MM */
    private String period;

    private BigDecimal totalAmount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
