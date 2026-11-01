package com.occ.smartexpense.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 建单时的单条明细（入参），对应 docs/03-接口清单.md §2.2。
 */
@Data
public class ReportItemDTO {

    @NotNull(message = "费用日期不能为空")
    private LocalDate expenseDate;

    @NotBlank(message = "费用类别不能为空")
    private String category;

    @NotNull(message = "金额不能为空")
    @DecimalMin(value = "0.01", message = "金额必须大于 0")
    private BigDecimal amount;

    private String description;

    /** 可选：关联的发票 id（先调 POST /invoices/upload 拿到，见 docs/03 §2.5） */
    private Long invoiceId;
}
