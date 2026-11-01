package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.entity.Invoice;
import com.occ.smartexpense.invoice.InvoiceRecognizer;
import com.occ.smartexpense.invoice.InvoiceRecognizerChain;
import com.occ.smartexpense.invoice.InvoiceValidator;
import com.occ.smartexpense.invoice.RecognitionException;
import com.occ.smartexpense.mapper.InvoiceMapper;
import com.occ.smartexpense.mapper.MqConsumeLogMapper;
import com.occ.smartexpense.service.InvoiceRecognitionService;
import com.occ.smartexpense.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 识别链路实现（docs/11 §3）。
 *
 * <p>刻意**不加 @Transactional**：模型调用可能 20s，不能占着数据库连接和事务；
 * 每一步用条件更新做状态机，重复消息靠 mq_consume_log + PENDING 抢占双保险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceRecognitionServiceImpl implements InvoiceRecognitionService {

    private static final String CONSUMER = "invoice-recognize";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_MANUAL = "MANUAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final BigDecimal FIELD_REVIEW_THRESHOLD = new BigDecimal("0.80");
    private static final int MAX_ERROR_LENGTH = 512;
    private static final int MAX_RAW_LENGTH = 2000;

    private final InvoiceMapper invoiceMapper;
    private final MqConsumeLogMapper consumeLogMapper;
    private final OutboxService outboxService;
    private final InvoiceRecognizerChain recognizerChain;
    private final InvoiceValidator invoiceValidator;
    private final ObjectMapper objectMapper;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.invoice.confidence-threshold:0.85}")
    private BigDecimal confidenceThreshold;

    @Override
    public void enqueue(Long invoiceId) {
        outboxService.append("INVOICE_UPLOADED", "INVOICE", invoiceId,
                "{\"invoiceId\":" + invoiceId + "}");
    }

    @Override
    public void consume(String msgId, String payloadJson) {
        // 1. 消费幂等：同一 msgId 只处理一次
        try {
            consumeLogMapper.insert(msgId, CONSUMER);
        } catch (DuplicateKeyException e) {
            log.info("发票识别消息重复，跳过 msgId={}", msgId);
            return;
        }
        long invoiceId = parseInvoiceId(payloadJson);
        if (invoiceId <= 0) {
            log.warn("发票识别事件缺少 invoiceId，丢弃 payload={}", payloadJson);
            return;
        }
        // 2. 抢占：PENDING → PROCESSING，抢不到说明重复消息/已被人工处理
        int claimed = invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, invoiceId)
                .eq(Invoice::getOcrStatus, STATUS_PENDING)
                .set(Invoice::getOcrStatus, STATUS_PROCESSING));
        if (claimed == 0) {
            log.info("发票 {} 不是 PENDING，跳过（重复消费或人工已处理）", invoiceId);
            return;
        }

        Invoice invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            return;
        }
        Path path = storageRoot().resolve(invoice.getFileUrl()).normalize();
        long startNanos = System.nanoTime();
        try {
            if (!path.startsWith(storageRoot()) || !Files.exists(path)) {
                throw new RecognitionException("发票文件不存在或路径非法");
            }
            InvoiceRecognizerChain.ChainResult chainResult =
                    recognizerChain.recognize(path.toString(), contentTypeOf(invoice.getFileUrl()));
            finish(invoiceId, chainResult, elapsedMs(startNanos));
        } catch (RecognitionException e) {
            // 业务失败：落 FAILED，等人工 retry；绝不 requeue（docs/11 §3.5）
            markFailed(invoiceId, e.getMessage(), elapsedMs(startNanos));
        } catch (Exception e) {
            log.error("发票识别异常 invoiceId={}", invoiceId, e);
            markFailed(invoiceId, "识别异常：" + e.getClass().getSimpleName(), elapsedMs(startNanos));
        }
    }

    /** 校验 + 置信度判定 + 回写（条件 PROCESSING，人工复核不会被重复消息覆盖） */
    private void finish(Long invoiceId, InvoiceRecognizerChain.ChainResult chainResult, int latencyMs) {
        InvoiceRecognizer.RecognitionResult result = chainResult.result();
        List<String> issues = invoiceValidator.validate(result);
        BigDecimal confidence = result.confidence();
        boolean lowConfidence = confidence != null && confidence.compareTo(confidenceThreshold) < 0;
        String status = issues.isEmpty() && !lowConfidence ? STATUS_SUCCESS : STATUS_MANUAL;
        String error = null;
        if (!issues.isEmpty()) {
            error = String.join(",", issues);
        } else if (lowConfidence) {
            error = "LOW_CONFIDENCE:" + confidence.toPlainString();
        }

        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        List<String> needsReview = new ArrayList<>();
        for (InvoiceRecognizer.FieldConfidence field : result.fields()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", field.name());
            row.put("value", field.value());
            row.put("confidence", field.confidence());
            row.put("source", field.source());
            fields.add(row);
            if (field.confidence() != null && field.confidence().compareTo(FIELD_REVIEW_THRESHOLD) < 0) {
                needsReview.add(field.name());
            }
        }
        model.put("fields", fields);
        model.put("needsReview", needsReview);
        model.put("raw", truncate(result.rawJson(), MAX_RAW_LENGTH));

        try {
            invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                    .eq(Invoice::getId, invoiceId)
                    .eq(Invoice::getOcrStatus, STATUS_PROCESSING)
                    .set(Invoice::getInvoiceCode, result.invoiceCode())
                    .set(Invoice::getInvoiceNo, result.invoiceNo())
                    .set(Invoice::getInvoiceDate, result.invoiceDate())
                    .set(Invoice::getAmount, result.amount())
                    .set(Invoice::getTaxAmount, result.taxAmount())
                    .set(Invoice::getConfidence, confidence)
                    .set(Invoice::getModelResult, writeJson(model))
                    .set(Invoice::getRecognizer, chainResult.recognizer())
                    .set(Invoice::getDegraded, chainResult.degraded() ? 1 : 0)
                    .set(Invoice::getOcrStatus, status)
                    .set(Invoice::getLatencyMs, latencyMs)
                    .set(Invoice::getErrorMsg, truncate(error, MAX_ERROR_LENGTH)));
        } catch (DuplicateKeyException e) {
            // 旧库若还保留 uk_invoice_no：票号撞键不是识别失败，落 MANUAL 交人工（docs/19 §4）
            log.warn("发票 {} 票号重复，转人工复核：{}", invoiceId, e.getMessage());
            invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                    .eq(Invoice::getId, invoiceId)
                    .eq(Invoice::getOcrStatus, STATUS_PROCESSING)
                    .set(Invoice::getInvoiceCode, null)
                    .set(Invoice::getInvoiceNo, null)
                    .set(Invoice::getInvoiceDate, result.invoiceDate())
                    .set(Invoice::getAmount, result.amount())
                    .set(Invoice::getTaxAmount, result.taxAmount())
                    .set(Invoice::getConfidence, confidence)
                    .set(Invoice::getModelResult, writeJson(model))
                    .set(Invoice::getRecognizer, chainResult.recognizer())
                    .set(Invoice::getDegraded, chainResult.degraded() ? 1 : 0)
                    .set(Invoice::getOcrStatus, STATUS_MANUAL)
                    .set(Invoice::getLatencyMs, latencyMs)
                    .set(Invoice::getErrorMsg, "DUPLICATE_INVOICE_NO"));
            return;
        }
        log.info("发票 {} 识别完成：{}（{}，degraded={}，{}ms）", invoiceId, status,
                chainResult.recognizer(), chainResult.degraded(), latencyMs);
    }

    private void markFailed(Long invoiceId, String message, int latencyMs) {
        invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, invoiceId)
                .eq(Invoice::getOcrStatus, STATUS_PROCESSING)
                .set(Invoice::getOcrStatus, STATUS_FAILED)
                .set(Invoice::getLatencyMs, latencyMs)
                .set(Invoice::getErrorMsg, truncate(message, MAX_ERROR_LENGTH)));
    }

    private long parseInvoiceId(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson).path("invoiceId").asLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"fields\":[],\"needsReview\":[]}";
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 识别耗时：nanotime 差转毫秒，上限 int 防溢出 */
    private int elapsedMs(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, ms));
    }

    private Path storageRoot() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    private String contentTypeOf(String key) {
        String lower = key == null ? "" : key.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    /** 保留给后续字段级统计：当前未使用，但别删——识别链路接入指标时会用到 */
    @SuppressWarnings("unused")
    private LocalDate today() {
        return LocalDate.now();
    }
}
