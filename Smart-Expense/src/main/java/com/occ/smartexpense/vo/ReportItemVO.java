package com.occ.smartexpense.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 详情页的单条明细（出参）。
 */
@Data
public class ReportItemVO {

    private Long id;

    private LocalDate expenseDate;

    private String category;

    private BigDecimal amount;

    private String description;
}
