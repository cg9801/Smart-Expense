package com.occ.smartexpense.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 部门预算出参（管理总览与预算管理页），口径见 docs/06 §2.1：
 * 可用额度 = total - used - frozen。
 */
@Data
public class BudgetVO {

    private Long deptId;

    private String deptName;

    /** YYYY-MM */
    private String period;

    private BigDecimal totalAmount;

    private BigDecimal usedAmount;

    private BigDecimal frozenAmount;

    /** total - used - frozen，可能为 0 */
    private BigDecimal availableAmount;

    /** 使用率 = (used + frozen) / total，0~1；total=0 时为 0 */
    private BigDecimal usageRate;
}
