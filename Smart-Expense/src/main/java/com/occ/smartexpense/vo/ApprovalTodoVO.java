package com.occ.smartexpense.vo;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ApprovalTodoVO {
    private Long approvalId;

    private Long reportId;

    private String reportNo;

    private String title;

    private String applicantName;

    private BigDecimal amountTotal;

    private Integer stepNo;

    private String stepName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deadlineAt;

    private String status;

}
