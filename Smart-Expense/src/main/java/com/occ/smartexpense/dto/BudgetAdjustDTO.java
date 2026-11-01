package com.occ.smartexpense.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 调整部门预算总额入参（管理端"分发预算"）。
 * 见 docs/03 §2.4 与 docs/06 §2.1（可用额度口径）。
 */
@Data
public class BudgetAdjustDTO {

    /** 账期 YYYY-MM，空则当前月 */
    private String period;

    @NotNull(message = "预算总额不能为空")
    @DecimalMin(value = "0", message = "预算总额不能为负")
    private BigDecimal totalAmount;

    /**
     * SET 覆盖（默认，兼容旧前端）/ ADD 追加。
     * 追加式入账写一条 ADJUST_ADD 流水，覆盖式写 ADJUST 流水（docs/03 §2.4）。
     */
    private String mode;
}
