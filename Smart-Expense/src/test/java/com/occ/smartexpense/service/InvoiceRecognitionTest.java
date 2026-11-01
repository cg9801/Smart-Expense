package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.dto.InvoiceReviewDTO;
import com.occ.smartexpense.entity.Invoice;
import com.occ.smartexpense.entity.OutboxMessage;
import com.occ.smartexpense.mapper.InvoiceMapper;
import com.occ.smartexpense.mapper.OutboxMessageMapper;
import com.occ.smartexpense.vo.InvoiceVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发票识别链路集成测试（docs/11 §3）：
 * 上传 → Outbox → 消费识别 → SUCCESS；跨人同图标记；人工复核；重试。
 *
 * <p>关掉 MQ 监听器，避免后台消费者和测试断言抢竞态；文件写到 target 下，构建时清理。
 */
@SpringBootTest(properties = {
        "app.upload.dir=target/test-uploads",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Transactional
class InvoiceRecognitionTest {

    private static final long UPLOADER_A = 1L;
    private static final long UPLOADER_B = 3L;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRecognitionService recognitionService;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    @Test
    void upload_then_consume_fills_fields_and_marks_success() {
        InvoiceVO uploaded = invoiceService.upload(file("a.png", "invoice-a"), UPLOADER_A);
        assertEquals("PENDING", uploaded.getOcrStatus());
        assertEquals(1L, outboxCount(uploaded.getId()));

        // mq_consume_log.msg_id 是 32 位：生产上就是 RabbitMQ 生成的 msgId，测试也要守这个宽度
        recognitionService.consume(UUID.randomUUID().toString().replace("-", ""),
                "{\"invoiceId\":" + uploaded.getId() + "}");

        InvoiceVO done = invoiceService.get(uploaded.getId());
        assertEquals("SUCCESS", done.getOcrStatus());
        assertNotNull(done.getInvoiceCode());
        assertNotNull(done.getInvoiceNo());
        assertNotNull(done.getInvoiceDate());
        // mock 金额由文件哈希派生（不同图不同值），只断言是合法金额；固定 3200 会让人误以为识别准
        assertNotNull(done.getAmount());
        assertTrue(done.getAmount().compareTo(BigDecimal.ZERO) > 0);
        assertEquals("mock", done.getRecognizer());
        assertEquals(0, done.getDegraded());
        assertFalse(done.getFields().isEmpty());
        assertTrue(done.getFields().stream().anyMatch(f -> "amount".equals(f.getName())));
    }

    @Test
    void same_image_from_two_users_is_allowed_and_flagged_as_duplicate_suspect() {
        byte[] content = "shared-invoice-image".getBytes();
        InvoiceVO first = invoiceService.upload(file("first.png", new String(content)), UPLOADER_A);
        InvoiceVO second = invoiceService.upload(file("second.png", new String(content)), UPLOADER_B);

        assertNotEquals(first.getId(), second.getId());
        assertEquals(Boolean.FALSE, first.getDuplicateSuspect());
        assertEquals(Boolean.TRUE, second.getDuplicateSuspect());
    }

    @Test
    void review_manual_invoice_sets_success_and_reviewer() {
        InvoiceVO uploaded = invoiceService.upload(file("review.png", "review-me"), UPLOADER_A);
        invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, uploaded.getId())
                .set(Invoice::getOcrStatus, "MANUAL")
                .set(Invoice::getErrorMsg, "LOW_CONFIDENCE:0.72"));

        InvoiceReviewDTO dto = new InvoiceReviewDTO();
        dto.setInvoiceCode("CODE-REVIEW");
        dto.setInvoiceNo("NO-REVIEW");
        dto.setInvoiceDate(LocalDate.of(2026, 10, 20));
        dto.setAmount(new BigDecimal("888.00"));
        dto.setTaxAmount(new BigDecimal("48.00"));

        InvoiceVO reviewed = invoiceService.review(uploaded.getId(), dto, 4L);

        assertEquals("SUCCESS", reviewed.getOcrStatus());
        assertEquals("CODE-REVIEW", reviewed.getInvoiceCode());
        assertEquals(0, new BigDecimal("888.00").compareTo(reviewed.getAmount()));
        assertEquals(0, new BigDecimal("1.000").compareTo(reviewed.getConfidence()));
        assertEquals(4L, invoiceMapper.selectById(uploaded.getId()).getReviewedBy());
    }

    @Test
    void retry_failed_invoice_republishes_event_and_counts_retry() {
        InvoiceVO uploaded = invoiceService.upload(file("retry.png", "retry-me"), UPLOADER_A);
        invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, uploaded.getId())
                .set(Invoice::getOcrStatus, "FAILED")
                .set(Invoice::getErrorMsg, "MODEL_DOWN"));
        long before = outboxCount(uploaded.getId());

        InvoiceVO retried = invoiceService.retry(uploaded.getId());

        assertEquals("PENDING", retried.getOcrStatus());
        assertEquals(1, retried.getRetryCount());
        assertEquals(before + 1, outboxCount(uploaded.getId()));
    }

    @Test
    void retry_pending_invoice_is_state_illegal() {
        InvoiceVO uploaded = invoiceService.upload(file("pending.png", "still-pending"), UPLOADER_A);

        BizException ex = assertThrows(BizException.class, () -> invoiceService.retry(uploaded.getId()));
        assertEquals(ErrorCode.STATE_ILLEGAL, ex.getErrorCode());
    }

    private long outboxCount(Long invoiceId) {
        Long count = outboxMessageMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getEventType, "INVOICE_UPLOADED")
                .eq(OutboxMessage::getAggregateId, invoiceId));
        return count == null ? 0 : count;
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "image/png", content.getBytes());
    }
}
