package com.occ.smartexpense.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发票出参，对应 docs/03-接口清单.md §2.5。
 * 识别链路接入后返回字段级置信度（docs/11 §4）：fields / needsReview / recognizer / degraded。
 */
@Data
public class InvoiceVO {

    private Long id;

    private Long reportId;

    private String originalName;

    /** 前端拼 /api/v1/invoices/{id}/file 下载，不暴露存储路径 */
    private String fileUrl;

    private String invoiceCode;

    private String invoiceNo;

    private LocalDate invoiceDate;

    private BigDecimal amount;

    private BigDecimal taxAmount;

    private String ocrStatus;

    private BigDecimal confidence;

    /** 识别失败/低置信原因：AMOUNT_INVALID、LOW_CONFIDENCE:0.72 等 */
    private String errorMsg;

    /** 字段级置信度，供辅助填入高亮 */
    private List<InvoiceFieldVO> fields;

    /** 低于 0.80 的字段名 */
    private List<String> needsReview;

    /** 实际产出的识别实现：mock / qwen-vl */
    private String recognizer;

    /** 1 表示走过降级链 */
    private Integer degraded;

    private Integer retryCount;

    /** 同 file_hash 被他人上传过：疑似一票多报，交审批人判断（docs/11 §3.4） */
    private Boolean duplicateSuspect;

    private LocalDateTime createdAt;
}
