package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预算流水 budget_txn（只追加），见 sql/schema.sql §2 与 docs/06-预算冻结与扣减.md §2.2。
 * biz_no 是业务幂等键：FREEZE:{reportId}:{submitToken} / RELEASE:{reportId}:{submitToken} / DEDUCT:{reportId}。
 */
@Data
@TableName("budget_txn")
public class BudgetTxn {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long budgetId;

    private Long reportId;

    /** 幂等键，唯一索引 uk_biz_no */
    private String bizNo;

    /** FREEZE / DEDUCT / RELEASE / ADJUST（覆盖式调整）/ ADJUST_ADD（追加） */
    private String txnType;

    /** 永远存正数，方向由 txnType 表达 */
    private BigDecimal amount;

    private LocalDateTime createdAt;
}
