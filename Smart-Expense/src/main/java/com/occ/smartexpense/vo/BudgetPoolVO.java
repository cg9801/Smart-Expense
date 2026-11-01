package com.occ.smartexpense.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 总预算池出参（管理端"设置总预算"）：总池、已分配（各部门 total 之和）、未分配。
 * 口径与 docs/06 §2.1 一致。
 */
@Data
public class BudgetPoolVO {

    private String period;

    private BigDecimal totalAmount;

    /** 各部门预算总额之和 */
    private BigDecimal allocatedAmount;

    /** total - allocated，可能为负（历史数据异常时） */
    private BigDecimal unallocatedAmount;
}
