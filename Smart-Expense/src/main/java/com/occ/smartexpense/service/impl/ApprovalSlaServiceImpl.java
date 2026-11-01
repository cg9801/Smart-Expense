package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.entity.ExpenseApproval;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import com.occ.smartexpense.entity.SysDept;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.mapper.ExpenseApprovalMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.ExpenseStatusLogMapper;
import com.occ.smartexpense.mapper.SysDeptMapper;
import com.occ.smartexpense.mapper.SysUserMapper;
import com.occ.smartexpense.service.ApprovalSlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SLA 扫描实现，规则严格照 docs/02 §7：
 * <ul>
 *   <li>首次超时：只写 TIMEOUT_REMIND 审计，同一节点 1 小时内不重复提醒；</li>
 *   <li>超时超过 1 个 SLA：原节点 SKIPPED，插入同 step_no 的新节点，写 TIMEOUT_ESCALATE；</li>
 *   <li>升级找不到人只记日志、不改数据——宁可升级失败，也不能把待办丢进黑洞。</li>
 * </ul>
 * 每个节点独立事务：一个节点失败不影响本轮其他节点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalSlaServiceImpl implements ApprovalSlaService {

    private static final String NODE_PENDING = "PENDING";
    private static final String NODE_SKIPPED = "SKIPPED";
    private static final String REPORT_PENDING = "PENDING";
    private static final String REPORT_APPROVING = "APPROVING";
    private static final String ACTION_REMIND = "TIMEOUT_REMIND";
    private static final String ACTION_ESCALATE = "TIMEOUT_ESCALATE";
    /** 同一节点的提醒间隔：定时任务 10 分钟一次，别把审计刷成流水账 */
    private static final long REMIND_INTERVAL_HOURS = 1L;

    private final ExpenseApprovalMapper approvalMapper;
    private final ExpenseReportMapper reportMapper;
    private final ExpenseStatusLogMapper statusLogMapper;
    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public int scanOverdue() {
        List<ExpenseApproval> overdue = approvalMapper.selectOverdue();
        int handled = 0;
        for (ExpenseApproval node : overdue) {
            try {
                Boolean done = transactionTemplate.execute(tx -> handleOne(node));
                if (Boolean.TRUE.equals(done)) {
                    handled++;
                }
            } catch (BizException e) {
                log.info("SLA 处理跳过 approvalId={}：{}", node.getId(), e.getMessage());
            } catch (Exception e) {
                log.warn("SLA 处理失败 approvalId={}", node.getId(), e);
            }
        }
        return handled;
    }

    /** 单节点处理；返回 true 表示写入了提醒或完成升级。 */
    private boolean handleOne(ExpenseApproval node) {
        LocalDateTime now = LocalDateTime.now();
        // 锁内复核：扫描到执行之间，节点可能已被审批/转交/撤回
        ExpenseApproval fresh = approvalMapper.selectById(node.getId());
        if (fresh == null || !NODE_PENDING.equals(fresh.getStatus()) || fresh.getDeadlineAt() == null) {
            return false;
        }
        ExpenseReport report = reportMapper.selectById(fresh.getReportId());
        if (report == null
                || !(REPORT_PENDING.equals(report.getStatus()) || REPORT_APPROVING.equals(report.getStatus()))) {
            return false;
        }

        long slaHours = slaHoursOf(fresh.getSourceRole());
        boolean escalate = now.isAfter(fresh.getDeadlineAt().plusHours(slaHours));

        if (!escalate) {
            LocalDateTime since = now.minusHours(REMIND_INTERVAL_HOURS);
            if (statusLogMapper.countRemind(fresh.getReportId(), fresh.getId(), since) > 0) {
                return false;
            }
            writeLog(fresh.getReportId(), ACTION_REMIND, report.getStatus(),
                    "{\"approvalId\":" + fresh.getId()
                            + ",\"approverId\":" + fresh.getApproverId()
                            + ",\"deadlineAt\":\"" + fresh.getDeadlineAt() + "\"}");
            return true;
        }

        SysUser target = findEscalationTarget(fresh, report);
        if (target == null || target.getId().equals(fresh.getApproverId())) {
            log.warn("SLA 升级找不到可用审批人 reportId={}, approvalId={}, role={}",
                    fresh.getReportId(), fresh.getId(), fresh.getSourceRole());
            return false;
        }
        // 同级同人已有节点：唯一键会冲突，提前跳过（例如已转交过）
        Long same = approvalMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, fresh.getReportId())
                .eq(ExpenseApproval::getStepNo, fresh.getStepNo())
                .eq(ExpenseApproval::getApproverId, target.getId()));
        if (same != null && same > 0) {
            return false;
        }

        // 原节点：PENDING → SKIPPED，条件更新防重复
        int updated = approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getId, fresh.getId())
                .eq(ExpenseApproval::getStatus, NODE_PENDING)
                .set(ExpenseApproval::getStatus, NODE_SKIPPED)
                .set(ExpenseApproval::getActedAt, now)
                .setSql("version = version + 1"));
        if (updated == 0) {
            return false;
        }

        // 新节点：同 step_no、新审批人、deadline 顺延一个 SLA
        ExpenseApproval escalated = new ExpenseApproval();
        escalated.setReportId(fresh.getReportId());
        escalated.setStepNo(fresh.getStepNo());
        escalated.setStepName(fresh.getStepName());
        escalated.setApprovalMode(fresh.getApprovalMode());
        escalated.setApproverId(target.getId());
        escalated.setApproverName(target.getRealName());
        escalated.setApproverDeptName(deptNameOf(target.getDeptId()));
        escalated.setSourceRole(fresh.getSourceRole());
        escalated.setStatus(NODE_PENDING);
        escalated.setDeadlineAt(now.plusHours(slaHours));
        escalated.setTransferFromId(fresh.getId());
        try {
            approvalMapper.insert(escalated);
        } catch (DuplicateKeyException e) {
            // 事务回滚掉刚刚的 SKIPPED，保持"要么换成，要么不变"
            throw new BizException(ErrorCode.STATE_ILLEGAL, "升级目标已有待办节点");
        }

        writeLog(fresh.getReportId(), ACTION_ESCALATE, report.getStatus(),
                "{\"fromApprovalId\":" + fresh.getId()
                        + ",\"fromApproverId\":" + fresh.getApproverId()
                        + ",\"toApprovalId\":" + escalated.getId()
                        + ",\"toApproverId\":" + target.getId()
                        + ",\"reason\":\"OVER_SLA\"}");
        return true;
    }

    /**
     * 升级目标：
     * 主管级 → 本部门负责人，找不到就逐级往上找上级部门；
     * 财务/分管 → 同角色另一位在职用户，再兜底 ADMIN。
     * 都不存在时返回 null，由调用方记日志跳过。
     */
    private SysUser findEscalationTarget(ExpenseApproval node, ExpenseReport report) {
        if ("SUPERVISOR".equals(node.getSourceRole())) {
            SysDept dept = report.getApplicantDeptId() == null ? null : sysDeptMapper.selectById(report.getApplicantDeptId());
            while (dept != null) {
                Long leaderId = dept.getLeaderUserId();
                if (leaderId != null && !leaderId.equals(node.getApproverId())) {
                    SysUser leader = sysUserMapper.selectById(leaderId);
                    if (leader != null && Integer.valueOf(1).equals(leader.getStatus())) {
                        return leader;
                    }
                }
                dept = dept.getParentId() == null || dept.getParentId() == 0
                        ? null : sysDeptMapper.selectById(dept.getParentId());
            }
            return null;
        }
        List<SysUser> sameRole = sysUserMapper.selectActiveByRoleCodeExclude(node.getSourceRole(), node.getApproverId());
        if (!sameRole.isEmpty()) {
            return sameRole.get(0);
        }
        return sysUserMapper.selectFirstActiveByRoleCode("ADMIN");
    }

    /** SLA 与提交时快照保持一致：主管 24h、财务 24h、分管 48h（docs/02 §7） */
    private long slaHoursOf(String sourceRole) {
        return switch (sourceRole == null ? "" : sourceRole) {
            case "DIRECTOR" -> 48L;
            case "FINANCE" -> 24L;
            default -> 24L;
        };
    }

    private String deptNameOf(Long deptId) {
        if (deptId == null) {
            return "";
        }
        SysDept dept = sysDeptMapper.selectById(deptId);
        return dept == null || dept.getName() == null ? "" : dept.getName();
    }

    /** 系统动作：operatorId 留空，traceId 由 SLA 自己生成 */
    private void writeLog(Long reportId, String action, String reportStatus, String detail) {
        ExpenseStatusLog statusLog = new ExpenseStatusLog();
        statusLog.setTraceId("SLA-" + UUID.randomUUID().toString().substring(0, 8));
        statusLog.setReportId(reportId);
        statusLog.setAction(action);
        statusLog.setFromStatus(reportStatus);
        statusLog.setToStatus(reportStatus);
        statusLog.setOperatorName("系统");
        statusLog.setDetail(detail);
        statusLogMapper.insert(statusLog);
    }
}
