package com.occ.smartexpense.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 设置公司总预算入参（管理端） */
@Data
public class BudgetPoolDTO {

    /** 账期 YYYY-MM，空则当前月 */
    private String period;

    @NotNull(message = "总预算不能为空")
    @DecimalMin(value = "0", message = "总预算不能为负")
    private BigDecimal totalAmount;
}
