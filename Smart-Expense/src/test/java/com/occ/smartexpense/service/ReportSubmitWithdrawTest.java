package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseApproval;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import com.occ.smartexpense.mapper.ExpenseApprovalMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.ExpenseStatusLogMapper;
import com.occ.smartexpense.mapper.SysUserMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报销单提交 / 撤回集成测试：覆盖 docs/02-状态机.md 的 T1、T2。
 * 每个用例跑在测试事务里并回滚，不污染演示数据；依赖 seed 用户 1（林一，部门 2，主管 2）。
 */
@SpringBootTest
@Transactional
class ReportSubmitWithdrawTest {

    /** seed 数据：林一，部门 2（技术部），直属主管张三 */
    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Autowired
    private ExpenseApprovalMapper approvalMapper;

    @Autowired
    private ExpenseStatusLogMapper statusLogMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    void submit_creates_pending_report_with_three_step_chain() {
        ReportDetailVO draft = createDraft();
        assertEquals("DRAFT", draft.getStatus());
        assertEquals(List.of("SUBMIT"), draft.getAvailableActions());

        ReportDetailVO submitted = reportService.submitReport(draft.getId(), APPLICANT_ID, "idem-submit-1", "trace-submit-1");

        assertEquals("PENDING", submitted.getStatus());
        assertEquals(1, submitted.getCurrentStep());
        assertEquals(3, submitted.getTotalSteps());
        assertEquals(List.of("WITHDRAW", "APPROVE", "REJECT"), submitted.getAvailableActions());

        // 库内：submit_token、提交时间、3 条审批链快照、1 条审计
        ExpenseReport saved = reportMapper.selectById(draft.getId());
        assertEquals("PENDING", saved.getStatus());
        assertEquals(1, saved.getCurrentStep());
        assertEquals(3, saved.getTotalSteps());
        assertEquals("idem-submit-1", saved.getSubmitToken());
        assertNotNull(saved.getSubmittedAt());

        List<ExpenseApproval> approvals = listApprovals(draft.getId());
        assertEquals(3, approvals.size());
        assertEquals(List.of(1, 2, 3), approvals.stream().map(ExpenseApproval::getStepNo).toList());
        assertEquals(List.of("直属主管", "财务", "分管"),
                approvals.stream().map(ExpenseApproval::getStepName).toList());
        assertEquals(List.of("SUPERVISOR", "FINANCE", "DIRECTOR"),
                approvals.stream().map(ExpenseApproval::getSourceRole).toList());
        assertEquals(List.of("张三", "王五", "赵六"),
                approvals.stream().map(ExpenseApproval::getApproverName).toList());
        assertEquals(List.of("技术部", "总公司", "总公司"),
                approvals.stream().map(ExpenseApproval::getApproverDeptName).toList());
        assertTrue(approvals.stream().allMatch(a -> "PENDING".equals(a.getStatus())));
        assertTrue(approvals.stream().allMatch(a -> "SINGLE".equals(a.getApprovalMode())));
        assertTrue(approvals.stream().allMatch(a -> a.getDeadlineAt() != null));

        List<ExpenseStatusLog> logs = listLogs(draft.getId());
        assertEquals(1, logs.size());
        ExpenseStatusLog log = logs.get(0);
        assertEquals("SUBMIT", log.getAction());
        assertEquals("DRAFT", log.getFromStatus());
        assertEquals("PENDING", log.getToStatus());
        assertEquals(APPLICANT_ID, log.getOperatorId());
        // 申请人姓名快照取自 sys_user，不硬编码，避免不同环境 seed 姓名差异导致误报
        assertEquals(sysUserMapper.selectById(APPLICANT_ID).getRealName(), log.getOperatorName());
        assertEquals("trace-submit-1", log.getTraceId());
        // detail 是 MySQL JSON 列，读取时会规范化（空格/键序可能变化），只断言内容片段
        assertNotNull(log.getDetail());
        assertTrue(log.getDetail().contains("totalSteps"));
        assertTrue(log.getDetail().contains("SUPERVISOR"));
        assertTrue(log.getDetail().contains("DIRECTOR"));
    }

    @Test
    void submit_twice_with_same_idempotency_key_does_not_duplicate_chain() {
        ReportDetailVO draft = createDraft();
        String key = "idem-submit-duplicate";

        ReportDetailVO first = reportService.submitReport(draft.getId(), APPLICANT_ID, key, "trace-first");
        ReportDetailVO second = reportService.submitReport(draft.getId(), APPLICANT_ID, key, "trace-second");

        assertEquals("PENDING", second.getStatus());
        assertEquals(first.getId(), second.getId());
        // 幂等命中后不再新增审批节点与审计
        assertEquals(3, listApprovals(draft.getId()).size());
        assertEquals(1, listLogs(draft.getId()).size());
        assertEquals("PENDING", reportMapper.selectById(draft.getId()).getStatus());
    }

    @Test
    void withdraw_returns_draft_and_cancels_all_pending_approvals() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), APPLICANT_ID, "idem-withdraw", "trace-submit");

        ReportDetailVO withdrawn = reportService.withdrawReport(draft.getId(), APPLICANT_ID, "trace-withdraw");

        assertEquals("DRAFT", withdrawn.getStatus());
        assertEquals(0, withdrawn.getCurrentStep());
        assertEquals(List.of("SUBMIT"), withdrawn.getAvailableActions());

        ExpenseReport saved = reportMapper.selectById(draft.getId());
        assertEquals("DRAFT", saved.getStatus());
        assertEquals(0, saved.getCurrentStep());
        assertNull(saved.getSubmitToken());
        assertNull(saved.getSubmittedAt());

        List<ExpenseApproval> approvals = listApprovals(draft.getId());
        assertEquals(3, approvals.size());
        assertTrue(approvals.stream().allMatch(a -> "CANCELLED".equals(a.getStatus())));

        List<ExpenseStatusLog> logs = listLogs(draft.getId());
        assertEquals(2, logs.size());
        ExpenseStatusLog withdrawLog = logs.get(1);
        assertEquals("WITHDRAW", withdrawLog.getAction());
        assertEquals("PENDING", withdrawLog.getFromStatus());
        assertEquals("DRAFT", withdrawLog.getToStatus());
        assertEquals("trace-withdraw", withdrawLog.getTraceId());
    }

    @Test
    void withdraw_again_after_withdraw_is_state_illegal() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), APPLICANT_ID, "idem-withdraw-twice", null);
        reportService.withdrawReport(draft.getId(), APPLICANT_ID, null);

        BizException ex = assertThrows(BizException.class,
                () -> reportService.withdrawReport(draft.getId(), APPLICANT_ID, null));
        assertEquals(ErrorCode.STATE_ILLEGAL, ex.getErrorCode());
    }

    @Test
    void withdraw_by_other_user_is_forbidden() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), APPLICANT_ID, "idem-withdraw-forbidden", null);

        // 张三（2）不是申请人，撤回必须被拒（docs/02 §3 T2 守卫①）
        BizException ex = assertThrows(BizException.class,
                () -> reportService.withdrawReport(draft.getId(), 2L, null));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        // 失败不影响单据：仍是 PENDING
        assertEquals("PENDING", reportMapper.selectById(draft.getId()).getStatus());
    }

    /** 建一张合法草稿：金额由服务端按明细合计写入，明细 1 条 3200.00 */
    private ReportDetailVO createDraft() {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 10, 18));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("北京-上海 高铁往返");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setTitle("10 月差旅报销");
        dto.setExpenseType("TRAVEL");
        dto.setRemark("集成测试草稿");
        dto.setItems(List.of(item));
        return reportService.createDraft(dto, APPLICANT_ID, APPLICANT_DEPT_ID);
    }

    private List<ExpenseApproval> listApprovals(Long reportId) {
        return approvalMapper.selectList(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, reportId)
                .orderByAsc(ExpenseApproval::getStepNo));
    }

    private List<ExpenseStatusLog> listLogs(Long reportId) {
        return statusLogMapper.selectList(new LambdaQueryWrapper<ExpenseStatusLog>()
                .eq(ExpenseStatusLog::getReportId, reportId)
                .orderByAsc(ExpenseStatusLog::getId));
    }
}
