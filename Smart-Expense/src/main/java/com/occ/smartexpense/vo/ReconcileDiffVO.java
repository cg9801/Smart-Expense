package com.occ.smartexpense.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 对账差异出参，见 docs/03 §2.6 与 docs/08 §3 */
@Data
public class ReconcileDiffVO {

    private Long id;

    private LocalDate bizDate;

    /** BUDGET_NOT_DEDUCTED / PAYMENT_MISSING / AMOUNT_MISMATCH / STATUS_STUCK */
    private String diffType;

    private Long reportId;

    private String detail;

    /** OPEN / REPAIRED / IGNORED */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime repairedAt;

    /** NULL 表示自动补偿 */
    private Long handlerId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
