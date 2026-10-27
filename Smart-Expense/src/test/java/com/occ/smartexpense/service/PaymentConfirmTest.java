package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import com.occ.smartexpense.entity.PaymentOrder;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.ExpenseStatusLogMapper;
import com.occ.smartexpense.mapper.PaymentOrderMapper;
import com.occ.smartexpense.vo.PaymentVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 付款单查询与回执确认（T6）集成测试，见 docs/02 §3 与 docs/03 §2.4。
 * 每个用例在测试事务里回滚；付款单直接用 Mapper 造，避免依赖 MQ 消费者。
 */
@SpringBootTest
@Transactional
class PaymentConfirmTest {

    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;
    private static final long FINANCE_ID = 4L;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private PaymentOrderMapper paymentMapper;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Autowired
    private ExpenseStatusLogMapper statusLogMapper;

    @Test
    void confirm_moves_payment_and_report_to_paid_and_writes_audit() {
        PaymentOrder payment = prepareApprovedReportWithPayment("3200.00", "3200.00");

        PaymentVO result = paymentService.confirm(payment.getId(), FINANCE_ID);

        assertEquals("PAID", result.getStatus());
        assertNotNull(result.getPaidAt());
        assertEquals("PAID", paymentMapper.selectById(payment.getId()).getStatus());
        assertEquals("PAID", reportMapper.selectById(payment.getReportId()).getStatus());

        List<ExpenseStatusLog> logs = statusLogMapper.selectList(
                new LambdaQueryWrapper<ExpenseStatusLog>()
                        .eq(ExpenseStatusLog::getReportId, payment.getReportId())
                        .eq(ExpenseStatusLog::getAction, "PAY"));
        assertEquals(1, logs.size());
        assertEquals("APPROVED", logs.get(0).getFromStatus());
        assertEquals("PAID", logs.get(0).getToStatus());
    }

    @Test
    void confirm_twice_is_state_illegal() {
        PaymentOrder payment = prepareApprovedReportWithPayment("3200.00", "3200.00");
        paymentService.confirm(payment.getId(), FINANCE_ID);

        BizException ex = assertThrows(BizException.class,
                () -> paymentService.confirm(payment.getId(), FINANCE_ID));
        assertEquals(ErrorCode.STATE_ILLEGAL, ex.getErrorCode());
    }

    @Test
    void confirm_with_amount_mismatch_is_rejected_and_payment_stays_created() {
        PaymentOrder payment = prepareApprovedReportWithPayment("3200.00", "3000.00");

        BizException ex = assertThrows(BizException.class,
                () -> paymentService.confirm(payment.getId(), FINANCE_ID));
        assertEquals(ErrorCode.STATE_ILLEGAL, ex.getErrorCode());
        assertEquals("CREATED", paymentMapper.selectById(payment.getId()).getStatus());
        assertEquals("APPROVED", reportMapper.selectById(payment.getReportId()).getStatus());
    }

    @Test
    void list_filters_by_status_payee_and_report_no() {
        PaymentOrder payment = prepareApprovedReportWithPayment("3200.00", "3200.00");
        paymentService.confirm(payment.getId(), FINANCE_ID);

        PageResult<PaymentVO> paid = paymentService.list("PAID", APPLICANT_ID, null, 1, 10);
        assertEquals(1, paid.getTotal());
        assertEquals(payment.getPaymentNo(), paid.getRecords().get(0).getPaymentNo());

        String reportNo = reportMapper.selectById(payment.getReportId()).getReportNo();
        PageResult<PaymentVO> byNo = paymentService.list(null, null, reportNo.substring(0, 8), 1, 10);
        assertEquals(1, byNo.getTotal());

        assertEquals(0, paymentService.list("CREATED", null, null, 1, 10).getTotal());
    }

    /** 造一张 APPROVED 的单 + 一条 CREATED 付款单（金额可故意不一致） */
    private PaymentOrder prepareApprovedReportWithPayment(String reportAmount, String paymentAmount) {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 10, 25));
        item.setCategory("交通");
        item.setAmount(new BigDecimal(reportAmount));
        item.setDescription("付款测试");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setApplicantId(APPLICANT_ID);
        dto.setApplicantDeptId(APPLICANT_DEPT_ID);
        dto.setTitle("付款确认测试");
        dto.setExpenseType("TRAVEL");
        dto.setItems(List.of(item));
        ReportDetailVO draft = reportService.createDraft(dto);

        ExpenseReport report = reportMapper.selectById(draft.getId());
        report.setStatus("APPROVED");
        report.setCurrentStep(3);
        report.setTotalSteps(3);
        reportMapper.updateById(report);

        PaymentOrder payment = new PaymentOrder();
        payment.setPaymentNo("PAYTEST" + System.nanoTime());
        payment.setReportId(report.getId());
        payment.setPayeeUserId(APPLICANT_ID);
        payment.setPayeeName("林一");
        payment.setAmount(new BigDecimal(paymentAmount));
        payment.setStatus("CREATED");
        paymentMapper.insert(payment);
        return payment;
    }
}
