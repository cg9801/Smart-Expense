package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.OutboxMessage;
import com.occ.smartexpense.entity.PaymentOrder;
import com.occ.smartexpense.entity.ReconcileDiff;
import com.occ.smartexpense.mapper.*;
import com.occ.smartexpense.vo.ReconcileRecordVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对账集成测试（docs/08 §3/§9）。
 *
 * <p>约定：
 * <ul>
 *   <li>业务日用 2030-01-01，避开演示数据（它们的 finished_at 在 2026-10-22）；</li>
 *   <li>@Transactional 跑完回滚，不会污染数据库；</li>
 *   <li>单据统一用 ReportService.createDraft 造，再直接改状态，保证字段完整。</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class ReconcileTest {

    /** 专门给测试用的业务日，与演示数据隔离 */
    private static final LocalDate BIZ_DATE = LocalDate.of(2030, 1, 1);

    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;

    @Autowired
    private ReconcileService reconcileService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Autowired
    private ReconcileDiffMapper diffMapper;

    @Autowired
    private ReconcileRecordMapper recordMapper;

    @Autowired
    PaymentOrderMapper paymentOrderMapper;

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    // ==================== 示例用例：本鱼写好了，照着这个套路往下填 ====================

    @Test
    @DisplayName("缺付款单 → 检出 PAYMENT_MISSING 并自动修复（重放消息）")
    void paymentMissing_detectedAndRepaired() {
        ExpenseReport report = approvedReport("1000.00", true, null);

        reconcileService.run(BIZ_DATE);

        List<ReconcileDiff> diffs = diffsOf(report.getId());
        assertEquals(1, diffs.size());
        assertEquals("PAYMENT_MISSING", diffs.get(0).getDiffType());
        assertEquals("REPAIRED", diffs.get(0).getStatus());
        // 自动修复走消息重放：事务内应该写了一条 outbox（回滚后消失，不会真发出去）
        assertTrue(outboxMessageMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getAggregateId, report.getId())) > 0);
    }


    // 缺实扣流水，且预算行不存在 → BUDGET_NOT_DEDUCTED 保持 OPEN，detail 里含 repairError
    @Test
    @DisplayName("缺实扣且预算行不可用 → OPEN + repairError")
    void budgetNotDeducted_repairFails() {
        // 用不存在的 budgetId：deduct 条件更新 0 行 → 修复失败保持 OPEN。
        // 不要依赖 seed 预算行的 frozen（运行库可能残留冻结，会让"修复"意外成功）
        ExpenseReport report = approvedReport("2000", true, 999999L);

        reconcileService.run(BIZ_DATE);
        List<ReconcileDiff> diffs = diffsOf(report.getId());
        ReconcileDiff diff = diffs.stream()
                .filter(d -> "BUDGET_NOT_DEDUCTED".equals(d.getDiffType()))
                .findFirst()
                .orElse(null);
        assertNotNull(diff);
        assertEquals("OPEN",diff.getStatus());
        assertTrue(diff.getDetail().contains("repairError"));

    }

    @Test
    @DisplayName("TODO：金额不一致 → AMOUNT_MISMATCH 保持 OPEN")
    void amountMismatch_detected() {
        ExpenseReport report = approvedReport("1000", true, null);
        PaymentOrder order = new PaymentOrder();
        order.setPaymentNo("PAYTEST" + System.nanoTime());
        order.setReportId(report.getId());
        order.setPayeeUserId(1L);
        order.setPayeeName("林一");
        order.setAmount(new BigDecimal("999.00"));
        order.setStatus("CREATED");
        paymentOrderMapper.insert(order);
        reconcileService.run(BIZ_DATE);
        // ④ 断言
        ReconcileDiff diff = diffsOf(report.getId()).stream()
                .filter(d -> "AMOUNT_MISMATCH".equals(d.getDiffType()))
                .findFirst().orElse(null);
        assertNotNull(diff);
        assertEquals("OPEN", diff.getStatus());              // 金额类不自动修
        assertTrue(diff.getDetail().contains("payment=999.00"));
        // TODO 你来写
    }

    @Test
    @DisplayName("TODO：状态卡死 → STATUS_STUCK")
    void statusStuck_detected() {
        ExpenseReport report = approvedReport("500", false, null);

        reconcileService.run(BIZ_DATE);
        ReconcileDiff diff = diffsOf(report.getId()).stream()
                .filter(d -> "STATUS_STUCK".equals(d.getDiffType()))
                .findFirst().orElse(null);
        assertNotNull(diff);
        assertEquals("OPEN", diff.getStatus());

    }
    
    @Test
    @DisplayName("TODO：重复跑同一天 → 批次幂等")
    void runTwice_isIdempotent() {
        approvedReport("100.00", true, null);

        ReconcileRecordVO first = reconcileService.run(BIZ_DATE);   // 返回 VO
        int firstDiffCount = first.getDiffCount();

        reconcileService.run(BIZ_DATE);                            // 第二次应直接返回已有批次

        assertEquals(1, recordMapper.selectCount(null));           // 只有一条批次
        assertEquals(firstDiffCount, diffMapper.selectCount(null));// diff 没翻倍
    }

    // ==================== 工具方法（可直接用） ====================

    /**
     * 造一张 APPROVED 单。
     *
     * @param amount         金额（同时作为唯一一条明细的金额）
     * @param withFinishedAt false 时 finished_at 置空，用于 STATUS_STUCK 场景
     * @param budgetId       预算行 id，null 表示"历史单"，会跳过缺扣检查
     */
    private ExpenseReport approvedReport(String amount, boolean withFinishedAt, Long budgetId) {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(BIZ_DATE.minusDays(1));
        item.setCategory("交通");
        item.setAmount(new BigDecimal(amount));
        item.setDescription("对账测试");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setTitle("对账测试单");
        dto.setExpenseType("TRAVEL");
        dto.setItems(List.of(item));
        ReportDetailVO draft = reportService.createDraft(dto, APPLICANT_ID, APPLICANT_DEPT_ID);

        ExpenseReport report = reportMapper.selectById(draft.getId());
        report.setStatus("APPROVED");
        report.setCurrentStep(3);
        report.setTotalSteps(3);
        report.setBudgetId(budgetId);
        report.setFinishedAt(withFinishedAt ? BIZ_DATE.atTime(10, 0) : null);
        reportMapper.updateById(report);
        return reportMapper.selectById(report.getId());
    }

    /** 只查这张单产生的差异，避免被库里其他数据干扰 */
    private List<ReconcileDiff> diffsOf(Long reportId) {
        return diffMapper.selectList(new LambdaQueryWrapper<ReconcileDiff>()
                .eq(ReconcileDiff::getReportId, reportId));
    }
}
