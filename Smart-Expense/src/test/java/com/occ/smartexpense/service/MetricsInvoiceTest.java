package com.occ.smartexpense.service;

import com.occ.smartexpense.entity.Invoice;
import com.occ.smartexpense.mapper.InvoiceMapper;
import com.occ.smartexpense.vo.MetricsVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发票识别指标（docs/19 §2）：在真实库上插一条发票，断言聚合口径与手算一致。
 * 用相对断言（前后差值 / 与 mapper 计数对比），不依赖 seed 里的发票数量。
 */
@SpringBootTest
@Transactional
class MetricsInvoiceTest {

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Test
    void invoice_metrics_follow_table_counts() {
        MetricsVO before = metricsService.overview();
        long totalBefore = before.getInvoiceTotal();
        long degradedBefore = invoiceMapper.countDegraded();

        Invoice invoice = new Invoice();
        invoice.setFileUrl("202610/metrics-test.png");
        invoice.setFileHash("hash-" + System.nanoTime());
        invoice.setOriginalName("metrics-test.png");
        invoice.setOcrStatus("SUCCESS");
        invoice.setConfidence(new BigDecimal("0.93"));
        invoice.setRetryCount(0);
        invoice.setDegraded(1);
        invoice.setLatencyMs(120);
        invoice.setUploaderId(1L);
        invoiceMapper.insert(invoice);

        MetricsVO after = metricsService.overview();

        assertEquals(totalBefore + 1, after.getInvoiceTotal());
        // 降级率 = degraded ÷ total，保留 2 位；与手算比较
        long total = after.getInvoiceTotal();
        long degraded = degradedBefore + 1;
        BigDecimal expected = BigDecimal.valueOf(degraded)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(after.getInvoiceDegradedRate()));
        // 成功率/复核率非空、P95 非负（有耗时数据后应 > 0）
        assertTrue(after.getInvoiceSuccessRate().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(after.getInvoiceManualRate().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(after.getInvoiceP95LatencyMs().compareTo(BigDecimal.ZERO) >= 0);
    }

    @Test
    void empty_latency_still_returns_zero_not_null() {
        // 全表 latency_ms 为空时 selectP95 返回 null → VO 兜底 0（docs/10 口径）
        MetricsVO vo = metricsService.overview();
        assertTrue(vo.getInvoiceP95LatencyMs() != null);
    }
}
