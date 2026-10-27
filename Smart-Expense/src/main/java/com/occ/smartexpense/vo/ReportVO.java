package com.occ.smartexpense.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 列表出参（不含明细），对应 docs/03-接口清单.md §2.2 GET /reports。
 */
@Data
public class ReportVO {

    private Long id;

    private String reportNo;

    private String title;

    private String expenseType;

    private BigDecimal amountTotal;

    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
