package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报销明细 expense_item，字段定义见 docs/01-表结构.md §4.1。
 */
@Data
@TableName("expense_item")
public class ExpenseItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    private LocalDate expenseDate;

    private String category;

    private BigDecimal amount;

    private String description;

    /** 关联发票，v2 才使用 */
    private Long invoiceId;

    private Integer sortNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
