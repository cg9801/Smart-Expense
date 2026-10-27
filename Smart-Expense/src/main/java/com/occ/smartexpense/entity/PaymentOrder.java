package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 付款单 payment_order，字段见 sql/schema.sql §4 与 docs/07 §3.3。
 * uk_report 保证"一单一付"：同一个报销单永远只可能有一张付款单。
 */
@Data
@TableName("payment_order")
public class PaymentOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** PAY{yyyyMMdd}{6位}，uk_payment_no */
    private String paymentNo;

    private Long reportId;

    private Long payeeUserId;

    private String payeeName;

    private BigDecimal amount;

    /** CREATED / PAID / FAILED */
    private String status;

    private LocalDateTime paidAt;

    private String remark;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
