package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseApproval;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import com.occ.smartexpense.mapper.ExpenseApprovalMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.ExpenseStatusLogMapper;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批增强能力集成测试：
 * 已办列表（GET /approvals/handled）、转交候选人（GET /approvals/transfer-candidates）、
 * SLA 提醒与升级（docs/02 §7）。全部基于 seed 数据：林一(1)/张三(2)/李四(3)/王五(4)/赵六(5)/admin(6)。
 */
@SpringBootTest
@Transactional
class ApprovalFeatureTest {

    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;
    /** seed：技术部负责人=张三(2)，总公司负责人=王五(4) */
    private static final long SUPERVISOR_ID = 2L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalSlaService slaService;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Autowired
    private ExpenseApprovalMapper approvalMapper;

    @Autowired
    private ExpenseStatusLogMapper statusLogMapper;

    @Test
    void handled_lists_my_approved_node_after_approve() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), "idem-handled", null);
        Long nodeId = currentNode(draft.getId()).getId();

        approvalService.approve(nodeId, SUPERVISOR_ID, "同意", null, null);

        List<ApprovalTodoVO> handled = approvalService.listHandled(SUPERVISOR_ID, 1, 10).getRecords();
        ApprovalTodoVO row = handled.stream()
                .filter(h -> draft.getId().equals(h.getReportId()))
                .findFirst()
                .orElseThrow();
        assertEquals("APPROVED", row.getStatus());
        assertEquals("同意", row.getComment());
        assertNotNull(row.getActedAt());
        // 已办不占待办：同一节点不应再出现在 pending 里
        List<ApprovalTodoVO> pending = approvalService.listPending(SUPERVISOR_ID, 1, 100).getRecords();
        assertTrue(pending.stream().noneMatch(p -> nodeId.equals(p.getApprovalId())));
    }

    @Test
    void transfer_candidates_exclude_self_and_historical_approvers() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), "idem-candidates", null);

        // 张三（当前审批人）看到的候选人：不能是自己，也不能是本单历史审批人（张三2/王五4/赵六5）
        List<Long> ids = approvalService.listTransferCandidates(SUPERVISOR_ID, draft.getId())
                .stream().map(c -> c.getUserId()).toList();
        assertFalse(ids.contains(SUPERVISOR_ID));
        assertFalse(ids.contains(4L));
        assertFalse(ids.contains(5L));
        // 林一(1)、李四(3)、admin(6) 仍可选
        assertTrue(ids.contains(3L));
    }

    @Test
    void sla_scan_writes_remind_log_only_once_per_window() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), "idem-sla-remind", null);
        ExpenseApproval node = currentNode(draft.getId());
        // 刚过期 1 小时：还没到"超时 1 个 SLA"，只提醒
        approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getId, node.getId())
                .set(ExpenseApproval::getDeadlineAt, LocalDateTime.now().minusHours(1)));

        slaService.scanOverdue();

        List<ExpenseStatusLog> logs = statusLogs(draft.getId());
        long remind = logs.stream().filter(l -> "TIMEOUT_REMIND".equals(l.getAction())).count();
        assertEquals(1L, remind);
        // detail 是 MySQL JSON 列，取出来会被规范化（会加空格），只断言键与节点 id
        assertTrue(logs.stream().anyMatch(l -> l.getDetail() != null
                && l.getDetail().contains("\"approvalId\"")
                && l.getDetail().contains(String.valueOf(node.getId()))));
        // 提醒不改节点状态
        assertEquals("PENDING", approvalMapper.selectById(node.getId()).getStatus());

        // 同一节点 1 小时内再扫不会重复写提醒
        slaService.scanOverdue();
        assertEquals(1L, statusLogs(draft.getId()).stream()
                .filter(l -> "TIMEOUT_REMIND".equals(l.getAction())).count());
    }

    @Test
    void sla_scan_escalates_to_department_leader_after_one_extra_sla() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), "idem-sla-escalate", null);
        ExpenseApproval node = currentNode(draft.getId());
        // 超时超过 1 个 SLA（24h）：deadline = now - 25h → 升级
        approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getId, node.getId())
                .set(ExpenseApproval::getDeadlineAt, LocalDateTime.now().minusHours(25)));

        slaService.scanOverdue();

        // 原节点 SKIPPED，同 step_no 插入新节点给王五（技术部负责人=原审批人张三 → 往上级部门找）
        ExpenseApproval old = approvalMapper.selectById(node.getId());
        assertEquals("SKIPPED", old.getStatus());
        List<ExpenseApproval> stepOne = approvalMapper.selectList(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, draft.getId())
                .eq(ExpenseApproval::getStepNo, 1)
                .eq(ExpenseApproval::getStatus, "PENDING"));
        assertEquals(1, stepOne.size());
        assertEquals(4L, stepOne.get(0).getApproverId());
        assertEquals(node.getId(), stepOne.get(0).getTransferFromId());
        // 审计：TIMEOUT_ESCALATE；单据状态不变
        assertTrue(statusLogs(draft.getId()).stream()
                .anyMatch(l -> "TIMEOUT_ESCALATE".equals(l.getAction())));
        assertEquals("PENDING", reportMapper.selectById(draft.getId()).getStatus());
    }

    private ExpenseApproval currentNode(Long reportId) {
        ExpenseReport report = reportMapper.selectById(reportId);
        return approvalMapper.selectOne(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, reportId)
                .eq(ExpenseApproval::getStepNo, report.getCurrentStep())
                .eq(ExpenseApproval::getStatus, "PENDING"));
    }

    private List<ExpenseStatusLog> statusLogs(Long reportId) {
        return statusLogMapper.selectList(new LambdaQueryWrapper<ExpenseStatusLog>()
                .eq(ExpenseStatusLog::getReportId, reportId)
                .orderByAsc(ExpenseStatusLog::getId));
    }

    private ReportDetailVO createDraft() {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 10, 18));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("北京-上海 高铁往返");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setApplicantId(APPLICANT_ID);
        dto.setApplicantDeptId(APPLICANT_DEPT_ID);
        dto.setTitle("审批能力测试");
        dto.setExpenseType("TRAVEL");
        dto.setRemark("ApprovalFeatureTest");
        dto.setItems(List.of(item));
        return reportService.createDraft(dto);
    }
}
