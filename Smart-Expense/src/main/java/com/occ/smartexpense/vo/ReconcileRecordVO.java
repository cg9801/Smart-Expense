package com.occ.smartexpense.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 对账批次出参，见 docs/03 §2.6 */
@Data
public class ReconcileRecordVO {

    private Long id;

    private LocalDate bizDate;

    private Integer scannedCount;

    private Integer diffCount;

    /** RUNNING / DONE / FAILED */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;
}
