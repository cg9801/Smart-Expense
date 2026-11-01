package com.occ.smartexpense.invoice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 本地 Mock 识别器：开发/压测/演示兜底，**不打真模型**（docs/11 §3.2）。
 *
 * <p>字段由文件内容哈希派生：同一张图结果稳定，不同图片得不同票号/金额/日期，
 * 避免演示时被误认为"识别准"。但它是**确定性假数据**，真实识别请开 qwen-vl（docs/18）。
 * 前端会按 {@code recognizer=mock} 标注「演示数据」。
 */
@Slf4j
@Component
public class MockInvoiceRecognizer implements InvoiceRecognizer {

    private static final HexFormat HEX = HexFormat.of();

    /** 全局概率阈值（docs/11 §4）：< 0.85 整单进复核队列 */
    @Value("${app.invoice.confidence-threshold:0.85}")
    private BigDecimal threshold;

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public RecognitionResult recognize(String filePath, String contentType) throws RecognitionException {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(filePath));
            String hash = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String code = "C" + hash.substring(0, 10).toUpperCase();
            String no = "N" + hash.substring(10, 18).toUpperCase();

            // 用哈希派生金额/日期：不同图片给出不同结果，但同一张图永远一致（可断言）
            long seed = Long.parseLong(hash.substring(0, 8), 16);
            BigDecimal amount = BigDecimal.valueOf(100L + seed % 200_000L)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal tax = amount.multiply(new BigDecimal("0.06"))
                    .setScale(2, RoundingMode.HALF_UP);
            LocalDate date = LocalDate.now().minusDays(1 + seed % 30);

            List<FieldConfidence> fields = new ArrayList<>();
            fields.add(new FieldConfidence("invoiceCode", code, new BigDecimal("0.97"), name()));
            fields.add(new FieldConfidence("invoiceNo", no, new BigDecimal("0.96"), name()));
            fields.add(new FieldConfidence("invoiceDate", date.toString(), new BigDecimal("0.94"), name()));
            fields.add(new FieldConfidence("amount", amount.toPlainString(), new BigDecimal("0.93"), name()));
            fields.add(new FieldConfidence("taxAmount", tax.toPlainString(), new BigDecimal("0.90"), name()));

            return new RecognitionResult(code, no, date, amount, tax, new BigDecimal("0.93"),
                    fields, "{\"mock\":true,\"threshold\":" + threshold + "}");
        } catch (Exception e) {
            throw new RecognitionException("Mock 识别失败：" + e.getMessage(), e);
        }
    }
}
