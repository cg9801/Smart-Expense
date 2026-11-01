package com.occ.smartexpense.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 人工复核入参（PUT /invoices/{id}/review，docs/03 §2.5）。
 * 复核人修正识别字段后置 SUCCESS，发票进入可用状态。
 */
@Data
public class InvoiceReviewDTO {

    private String invoiceCode;

    private String invoiceNo;

    private LocalDate invoiceDate;

    @NotNull(message = "发票金额不能为空")
    @DecimalMin(value = "0.01", message = "发票金额必须大于 0")
    private BigDecimal amount;

    private BigDecimal taxAmount;
}
