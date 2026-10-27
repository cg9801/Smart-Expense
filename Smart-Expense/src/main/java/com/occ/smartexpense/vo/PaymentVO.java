package com.occ.smartexpense.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 付款单列表/详情出参，字段与 docs/03-接口清单.md §2.4 对应。
 * 金额用 BigDecimal，避免前端拿到 double 丢精度。
 */
@Data
public class PaymentVO {

    private Long id;

    private String paymentNo;

    private Long reportId;

    private String reportNo;

    private String title;

    private Long payeeUserId;

    private String payeeName;

    private BigDecimal amount;

    /** CREATED / PAID / FAILED */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paidAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
