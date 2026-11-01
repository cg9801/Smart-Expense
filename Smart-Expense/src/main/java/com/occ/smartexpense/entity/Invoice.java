package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 发票 invoice，字段定义见 sql/schema.sql §6。
 * 当前实现为 v2 的「方案 A 最小可用」：只落库与关联，不做识别（ocr_status 直接 MANUAL）。
 */
@Data
@TableName("invoice")
public class Invoice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    /** 相对存储键（如 202610/uuid.png），不存公网直链 */
    private String fileUrl;

    /** SHA-256，用于重复上传查重 */
    private String fileHash;

    private String originalName;

    private String invoiceCode;

    private String invoiceNo;

    private LocalDate invoiceDate;

    private BigDecimal amount;

    private BigDecimal taxAmount;

    /** PENDING / PROCESSING / SUCCESS / FAILED / MANUAL */
    private String ocrStatus;

    private BigDecimal confidence;

    /** 模型原始输出（JSON 字符串，方案 A 不写） */
    private String modelResult;

    private String errorMsg;

    private Integer retryCount;

    /** 实际产出的识别实现：mock / qwen-vl / cloud-ocr（docs/11 §3.2） */
    private String recognizer;

    /** 是否走过降级链：1 表示主实现失败、备用实现产出 */
    private Integer degraded;

    /** 单次识别耗时（毫秒），P95 指标用；不含 MQ 排队 */
    private Integer latencyMs;

    private Long uploaderId;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
