package com.occ.smartexpense.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 识别器抽象（docs/11 §3.2）：把「图」变成「字段」，不关心字段之后怎么用。
 * 实现可替换：mock（开发/压测）、qwen-vl（多模态）、cloud-ocr（后续扩展点）。
 */
public interface InvoiceRecognizer {

    /** 实现名，落库到 invoice.recognizer，便于统计各实现可用率 */
    String name();

    /**
     * 识别一张发票图片/PDF。
     *
     * @param filePath    物理路径
     * @param contentType MIME（image/png、application/pdf…）
     * @throws RecognitionException 识别器不可用、超时、输出无法解析
     */
    RecognitionResult recognize(String filePath, String contentType) throws RecognitionException;

    /** 字段级置信度，供前端"低置信度高亮"（docs/11 §4） */
    record FieldConfidence(String name, String value, BigDecimal confidence, String source) {
    }

    /** 识别结果：业务字段 + 字段级置信度 + 原始 JSON（排查/回放用） */
    record RecognitionResult(
            String invoiceCode,
            String invoiceNo,
            LocalDate invoiceDate,
            BigDecimal amount,
            BigDecimal taxAmount,
            BigDecimal confidence,
            List<FieldConfidence> fields,
            String rawJson) {
    }
}
