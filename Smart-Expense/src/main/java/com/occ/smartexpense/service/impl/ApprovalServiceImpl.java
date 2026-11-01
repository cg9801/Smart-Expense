package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import tools.jackson.databind.ObjectMapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.entity.AiReview;
import com.occ.smartexpense.entity.ExpenseApproval;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import com.occ.smartexpense.entity.SysDept;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.enums.ReportEvent;
import com.occ.smartexpense.enums.ReportStatus;
import com.occ.smartexpense.mapper.AiReviewMapper;
import com.occ.smartexpense.mapper.ExpenseApprovalMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.ExpenseStatusLogMapper;
import com.occ.smartexpense.mapper.SysDeptMapper;
import com.occ.smartexpense.mapper.SysUserMapper;
import com.occ.smartexpense.service.ApprovalService;
import com.occ.smartexpense.service.BudgetService;
import com.occ.smartexpense.service.OutboxService;
import com.occ.smartexpense.service.ReportService;
import com.occ.smartexpense.statemachine.ReportStateMachine;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import com.occ.smartexpense.vo.TransferCandidateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审批动作实现，步骤与 docs/05-工程结构.md §5 一一对应（定稿）。
 * 固定顺序：锁整级 → 更新自己 → 判本级 → 推进单据 → 写审计。
 */
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    /** 事件体时间格式（docs/07 §5） */
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExpenseApprovalMapper approvalMapper;
    private final ExpenseReportMapper reportMapper;
    private final ExpenseStatusLogMapper statusLogMapper;
    /** 记录 AI 建议是否被采纳（docs/11 §5.7），只更新 adopted 列 */
    private final AiReviewMapper aiReviewMapper;
    private final ReportStateMachine stateMachine;
    private final ReportService reportService;
    /** 驳回需要释放冻结（docs/06 §4.2） */
    private final BudgetService budgetService;
    /** 末级通过写 Outbox 事件（docs/07 §5） */
    private final OutboxService outboxService;
    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PageResult<ApprovalTodoVO> listPending(Long approverId, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 10;
        } else if (size > 100) {
            size = 100;
        }
        int offset = (page - 1) * size;
        long total = approvalMapper.countPending(approverId);
        if (total == 0) {
            return PageResult.empty(page, size);
        }
        List<ApprovalTodoVO> records = approvalMapper.selectPending(approverId, offset, size);
        return PageResult.of(records, total, page, size);
    }

    @Override
    public PageResult<ApprovalTodoVO> listHandled(Long approverId, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 10;
        } else if (size > 100) {
            size = 100;
        }
        int offset = (page - 1) * size;
        long total = approvalMapper.countHandled(approverId);
        if (total == 0) {
            return PageResult.empty(page, size);
        }
        return PageResult.of(approvalMapper.selectHandled(approverId, offset, size), total, page, size);
    }

    @Override
    public List<TransferCandidateVO> listTransferCandidates(Long selfId, Long reportId) {
        return sysUserMapper.selectTransferCandidates(selfId, reportId);
    }

    @Override
    @Transactional
    public ReportDetailVO approve(Long approvalId, Long userId, String comment, Long aiReviewId, Boolean aiAdopted) {
        // 1–2 步：取节点 + 校验存在/归属/未处理（docs/05 §5.2）
        ExpenseApproval node = loadPendingNode(approvalId, userId);

        // 3 步：先锁本级，避免同级两人各自持有对方要锁的行而死锁
        List<ExpenseApproval> stepNodes = lockStepNodes(node);

        // 4 步：锁内复核，别人可能刚处理完
        ExpenseApproval fresh = findInStep(stepNodes, approvalId);
        if (fresh == null || !"PENDING".equals(fresh.getStatus())) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "该节点已处理");
        }

        LocalDateTime now = LocalDateTime.now();
        // 5 步：条件更新自己那条，WHERE status='PENDING' 保证只成功一次
        int affect = approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getId, approvalId)
                .eq(ExpenseApproval::getStatus, "PENDING")
                .set(ExpenseApproval::getStatus, "APPROVED")
                .set(ExpenseApproval::getActedAt, now)
                .set(comment != null && !comment.isBlank(), ExpenseApproval::getComment, comment));
        if (affect == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "该节点已处理");
        }
        // stepNodes 是 UPDATE 之前的快照，必须同步，否则本级永远判不过
        fresh.setStatus("APPROVED");

        // 6 步：本级是否完成
        boolean stepPassed = judgeStepPassed(node.getApprovalMode(), stepNodes, approvalId);

        // 7 步：未完成，等同级其他人
        if (!stepPassed) {
            return reportService.getDetail(node.getReportId());
        }

        // 8 步：推进单据
        ExpenseReport report = requireReport(node.getReportId());
        boolean lastStep = node.getStepNo().equals(report.getTotalSteps());
        ReportStatus to = stateMachine.approve(ReportStatus.valueOf(report.getStatus()), lastStep);

        LambdaUpdateWrapper<ExpenseReport> reportUpdate = new LambdaUpdateWrapper<ExpenseReport>()
                .eq(ExpenseReport::getId, report.getId())
                .eq(ExpenseReport::getCurrentStep, node.getStepNo())
                .in(ExpenseReport::getStatus, "PENDING", "APPROVING");
        if (lastStep) {
            reportUpdate.set(ExpenseReport::getStatus, to.name())
                    .set(ExpenseReport::getFinishedAt, now);
        } else {
            reportUpdate.set(ExpenseReport::getCurrentStep, node.getStepNo() + 1)
                    .set(ExpenseReport::getStatus, to.name());
        }
        int rows = reportMapper.update(null, reportUpdate);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "单据状态已变更");
        }

        // 或签：同级其余待处理节点直接作废（docs/02 §4 SKIPPED）
        if ("OR".equals(node.getApprovalMode())) {
            approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                    .eq(ExpenseApproval::getReportId, node.getReportId())
                    .eq(ExpenseApproval::getStepNo, node.getStepNo())
                    .ne(ExpenseApproval::getId, node.getId())
                    .eq(ExpenseApproval::getStatus, "PENDING")
                    .set(ExpenseApproval::getStatus, "SKIPPED"));
        }

        // T4 副作用：末级通过时冻结转实扣（docs/06 §4.3）+ 写 Outbox（docs/07 §5），与审批同事务
        if (lastStep) {
            budgetService.deduct(report);
            appendReportApproved(report, now);
        }

        // AI 建议采纳结果落 ai_review（未表态时两个参数都为 null，不更新）
        markAdopted(node.getReportId(), aiReviewId, aiAdopted);

        // 审计永远最后写，中途失败才不会有假日志
        writeStatusLog(report.getId(), "APPROVE", report.getStatus(), to.name(),
                userId, node.getApproverName(), stepDetail(node, null, aiReviewId, aiAdopted));
        return reportService.getDetail(report.getId());
    }

    @Override
    @Transactional
    public ReportDetailVO reject(Long approvalId, Long userId, String comment, Long aiReviewId, Boolean aiAdopted) {
        // 1 步：驳回意见必填（docs/05 §5.3）
        if (comment == null || comment.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "驳回必须填写审批意见");
        }

        // 2 步：同 approve 的取节点 + 锁本级 + 复核
        ExpenseApproval node = loadPendingNode(approvalId, userId);
        List<ExpenseApproval> stepNodes = lockStepNodes(node);

        ExpenseApproval fresh = findInStep(stepNodes, approvalId);
        if (fresh == null || !"PENDING".equals(fresh.getStatus())) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "该节点已处理");
        }

        ExpenseReport report = requireReport(node.getReportId());
        // 3 步：状态机校验，非法流转直接 40901
        ReportStatus to = stateMachine.transit(ReportStatus.valueOf(report.getStatus()), ReportEvent.REJECT);

        LocalDateTime now = LocalDateTime.now();
        // 4 步：自己那条 → REJECTED
        int affect = approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getId, approvalId)
                .eq(ExpenseApproval::getStatus, "PENDING")
                .set(ExpenseApproval::getStatus, "REJECTED")
                .set(ExpenseApproval::getActedAt, now)
                .set(ExpenseApproval::getComment, comment));
        if (affect == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "该节点已处理");
        }

        // 5 步：该单其余待处理节点全部作废（驳回后整条链都走不到，docs/02 T5）
        approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, node.getReportId())
                .ne(ExpenseApproval::getId, node.getId())
                .eq(ExpenseApproval::getStatus, "PENDING")
                .set(ExpenseApproval::getStatus, "CANCELLED"));

        // 6 步：单据 → REJECTED
        int rows = reportMapper.update(null, new LambdaUpdateWrapper<ExpenseReport>()
                .eq(ExpenseReport::getId, report.getId())
                .in(ExpenseReport::getStatus, "PENDING", "APPROVING")
                .set(ExpenseReport::getStatus, to.name())
                .set(ExpenseReport::getFinishedAt, now));
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "单据状态已变更");
        }

        // T5 副作用：释放冻结（docs/06 §4.2），与单据状态同事务
        budgetService.release(report);

        // AI 建议采纳结果落 ai_review（未表态时不更新）
        markAdopted(node.getReportId(), aiReviewId, aiAdopted);

        // 7 步：审计
        writeStatusLog(report.getId(), "REJECT", report.getStatus(), to.name(),
                userId, node.getApproverName(), stepDetail(node, comment, aiReviewId, aiAdopted));
        return reportService.getDetail(report.getId());
    }

    /**
     * 转交（docs/02 §5）：原行 PENDING → TRANSFERRED，插入同 step_no 的新 PENDING 行。
     * 不改变单据状态；目标不得是本单历史审批人（防环）。
     */
    @Override
    @Transactional
    public ReportDetailVO transfer(Long approvalId, Long userId, Long targetUserId, String comment) {
        // 1 守卫：节点存在、属于我、且 PENDING（loadPendingNode 已覆盖）
        ExpenseApproval node = loadPendingNode(approvalId, userId);

        // 2 守卫：目标存在且在职，不能转给自己
        if (targetUserId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "转交目标用户不能为空");
        }
        if (targetUserId.equals(userId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能转交给自己");
        }
        SysUser target = sysUserMapper.selectById(targetUserId);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "转交目标用户不存在");
        }
        if (target.getStatus() != null && target.getStatus() == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "转交目标用户已离职");
        }

        // 3 防环：目标不能是本单任意历史审批人，否则甲乙互转会死循环
        Long historical = approvalMapper.selectCount(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, node.getReportId())
                .eq(ExpenseApproval::getApproverId, targetUserId));
        if (historical != null && historical > 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "不能转交给本单历史审批人（防环）");
        }

        LocalDateTime now = LocalDateTime.now();
        // 4 原行条件更新 PENDING → TRANSFERRED，并发重复转交只有一次成功
        int affect = approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getId, approvalId)
                .eq(ExpenseApproval::getStatus, "PENDING")
                .set(ExpenseApproval::getStatus, "TRANSFERRED")
                .set(ExpenseApproval::getActedAt, now)
                .set(comment != null && !comment.isBlank(), ExpenseApproval::getComment, comment));
        if (affect == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "该节点已处理");
        }

        // 5 插入新行：同 step_no，继承 SLA 与来源角色，transfer_from_id 指回原行
        String deptName = deptName(target.getDeptId());
        ExpenseApproval fresh = new ExpenseApproval();
        fresh.setReportId(node.getReportId());
        fresh.setStepNo(node.getStepNo());
        fresh.setStepName(node.getStepName());
        fresh.setApprovalMode(node.getApprovalMode());
        fresh.setApproverId(target.getId());
        fresh.setApproverName(target.getRealName());
        fresh.setApproverDeptName(deptName == null ? "" : deptName);
        fresh.setSourceRole(node.getSourceRole());
        fresh.setStatus("PENDING");
        fresh.setDeadlineAt(node.getDeadlineAt());
        fresh.setTransferFromId(node.getId());
        approvalMapper.insert(fresh);

        // 6 审计：转交不改变单据状态，from = to = 当前状态（docs/02 §8）
        ExpenseReport report = requireReport(node.getReportId());
        writeStatusLog(report.getId(), "TRANSFER", report.getStatus(), report.getStatus(),
                userId, node.getApproverName(),
                "{\"stepNo\":" + node.getStepNo()
                        + ",\"approvalId\":" + node.getId()
                        + ",\"newApprovalId\":" + fresh.getId()
                        + ",\"targetUserId\":" + targetUserId
                        + (comment != null ? ",\"comment\":\"" + escapeJson(comment) + "\"" : "") + "}");
        return reportService.getDetail(report.getId());
    }

    /** 目标用户的部门名快照（组织调整不影响在途节点展示） */
    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        SysDept dept = sysDeptMapper.selectById(deptId);
        return dept == null ? null : dept.getName();
    }

    /**
     * 通过规则（docs/02 §4）：作废/转交/跳过不参与计算。
     * SINGLE 看本人，AND 要求非空且全通过，OR 任一通过即可。
     */
    private boolean judgeStepPassed(String mode, List<ExpenseApproval> stepNodes, Long currentId) {        List<ExpenseApproval> effective = stepNodes.stream()
                .filter(n -> !"CANCELLED".equals(n.getStatus())
                        && !"TRANSFERRED".equals(n.getStatus())
                        && !"SKIPPED".equals(n.getStatus()))
                .toList();

        return switch (mode == null ? "SINGLE" : mode) {
            case "AND" -> !effective.isEmpty()
                    && effective.stream().allMatch(n -> "APPROVED".equals(n.getStatus()));
            case "OR" -> effective.stream().anyMatch(n -> "APPROVED".equals(n.getStatus()));
            default -> effective.stream().anyMatch(n -> n.getId().equals(currentId) && "APPROVED".equals(n.getStatus()));
        };
    }

    /** 3 步：对"本级全部节点"加行锁，锁顺序固定为 整级 → 自己 */
    private List<ExpenseApproval> lockStepNodes(ExpenseApproval node) {
        return approvalMapper.selectList(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, node.getReportId())
                .eq(ExpenseApproval::getStepNo, node.getStepNo())
                .last("FOR UPDATE"));
    }

    private ExpenseApproval findInStep(List<ExpenseApproval> stepNodes, Long approvalId) {
        return stepNodes.stream()
                .filter(n -> n.getId().equals(approvalId))
                .findFirst()
                .orElse(null);
    }

    private ExpenseReport requireReport(Long reportId) {
        ExpenseReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        return report;
    }

    private ExpenseApproval loadPendingNode(Long approvalId, Long userId) {
        ExpenseApproval node = approvalMapper.selectById(approvalId);
        if (node == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "审批任务不存在");
        }
        if (!node.getApproverId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "这不是你的任务");
        }
        if (!"PENDING".equals(node.getStatus())) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "该节点已处理");
        }
        return node;
    }

    /** 一次审批请求只写一条审计，from/to 记单据级状态（docs/05 §5.5） */
    private void writeStatusLog(Long reportId, String action, String fromStatus, String toStatus,
                                Long operatorId, String operatorName, String detail) {
        ExpenseStatusLog log = new ExpenseStatusLog();
        log.setTraceId("APV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        log.setReportId(reportId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setDetail(detail);
        statusLogMapper.insert(log);
    }

    private String stepDetail(ExpenseApproval node, String comment, Long aiReviewId, Boolean aiAdopted) {
        StringBuilder json = new StringBuilder()
                .append("{\"stepNo\":").append(node.getStepNo())
                .append(",\"approvalId\":").append(node.getId());
        if (comment != null) {
            json.append(",\"comment\":\"").append(escapeJson(comment)).append('"');
        }
        if (aiReviewId != null) {
            json.append(",\"aiReviewId\":").append(aiReviewId);
        }
        if (aiAdopted != null) {
            json.append(",\"aiAdopted\":").append(aiAdopted);
        }
        return json.append('}').toString();
    }

    /** 审批人采纳/忽略 AI 建议：只允许更新本单对应的那条建议 */
    private void markAdopted(Long reportId, Long aiReviewId, Boolean aiAdopted) {
        if (aiReviewId == null || aiAdopted == null) {
            return;
        }
        aiReviewMapper.update(null, new LambdaUpdateWrapper<AiReview>()
                .eq(AiReview::getId, aiReviewId)
                .eq(AiReview::getReportId, reportId)
                .set(AiReview::getAdopted, aiAdopted ? 1 : 0));
    }

    private String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 写 REPORT_APPROVED 事件到本地消息表（docs/07 §5）。
     * payload 是快照：消费者只依赖它，不回查业务表，避免"消息比事务先到"的时序问题。
     */
    private void appendReportApproved(ExpenseReport report, LocalDateTime approvedAt) {
        SysUser applicant = sysUserMapper.selectById(report.getApplicantId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", report.getId());
        payload.put("reportNo", report.getReportNo());
        payload.put("applicantId", report.getApplicantId());
        payload.put("applicantName", applicant == null ? null : applicant.getRealName());
        payload.put("amountTotal", report.getAmountTotal().toPlainString());
        payload.put("currency", report.getCurrency());
        payload.put("approvedAt", approvedAt.format(EVENT_TIME));
        // Jackson 3 的 JacksonException 是 RuntimeException，序列化失败会直接让事务回滚
        String json = objectMapper.writeValueAsString(payload);
        outboxService.append("REPORT_APPROVED", "REPORT", report.getId(), json);
    }
}
