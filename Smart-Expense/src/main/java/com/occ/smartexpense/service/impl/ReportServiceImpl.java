package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseApproval;
import com.occ.smartexpense.entity.ExpenseItem;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.SysDept;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.enums.ReportEvent;
import com.occ.smartexpense.enums.ReportStatus;
import com.occ.smartexpense.mapper.ExpenseApprovalMapper;
import com.occ.smartexpense.mapper.ExpenseItemMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.ExpenseStatusLogMapper;
import com.occ.smartexpense.mapper.SysDeptMapper;
import com.occ.smartexpense.mapper.SysUserMapper;
import com.occ.smartexpense.security.CurrentUser;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.AiReviewService;
import com.occ.smartexpense.service.BudgetService;
import com.occ.smartexpense.service.InvoiceService;
import com.occ.smartexpense.service.OutboxService;
import com.occ.smartexpense.service.ReportService;
import com.occ.smartexpense.statemachine.ReportStateMachine;
import com.occ.smartexpense.vo.ReportDetailVO;
import com.occ.smartexpense.vo.ReportItemVO;
import com.occ.smartexpense.vo.ReportVO;
import com.occ.smartexpense.vo.TimelineVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 报销单业务实现：事务边界、金额校验、编号生成都在这一层。
 * 状态流转规则见 docs/02-状态机.md，所有状态判定统一委托 {@link ReportStateMachine}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final String STATUS_DRAFT = "DRAFT";
    /** 单据状态与审批节点状态共用 PENDING 码值，含义随上下文区分 */
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVING = "APPROVING";
    private static final String NODE_CANCELLED = "CANCELLED";
    private static final String NODE_SKIPPED = "SKIPPED";
    private static final String APPROVAL_MODE_SINGLE = "SINGLE";
    private static final String ROLE_SUPERVISOR = "SUPERVISOR";
    private static final String ROLE_FINANCE = "FINANCE";
    private static final String ROLE_DIRECTOR = "DIRECTOR";
    private static final String ACTION_SUBMIT = "SUBMIT";
    private static final String ACTION_WITHDRAW = "WITHDRAW";
    /** 审批链每级 SLA：直属主管 24h、财务 24h、分管 48h（docs/02 §7） */
    private static final long SUPERVISOR_SLA_HOURS = 24L;
    private static final long FINANCE_SLA_HOURS = 24L;
    private static final long DIRECTOR_SLA_HOURS = 48L;
    private static final DateTimeFormatter REPORT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 事件体时间格式，与 docs/07 §5 保持一致 */
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExpenseReportMapper reportMapper;
    private final ExpenseItemMapper itemMapper;
    private final ExpenseApprovalMapper approvalMapper;
    private final ExpenseStatusLogMapper statusLogMapper;
    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final ReportStateMachine stateMachine;
    /**
     * 提交需要"先写快照、再抢 token"，并发撞 uk_submit_token 时要捕获 DuplicateKeyException 并回放详情。
     * 用编程式事务保证异常时快照整体回滚，又能在事务外回查同一 token 的单据返回幂等结果。
     */
    private final TransactionTemplate transactionTemplate;
    /** 预算冻结（T1 守卫③），见 docs/06-预算冻结与扣减.md */
    private final BudgetService budgetService;
    /** 提交事件写 Outbox，交给 AI 审核消费者异步处理（docs/11 §5.2） */
    private final OutboxService outboxService;
    /** 详情里附带 AI 建议（只读，失败不影响主流程） */
    private final AiReviewService aiReviewService;
    /** 发票绑定与详情展示（v2 方案 A：上传后关联，docs/11 §3） */
    private final InvoiceService invoiceService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDetailVO createDraft(ReportCreateDTO dto, Long userId, Long deptId) {
        // 金额以明细合计为准，不信任前端传来的总额（docs/02 §3 T1 守卫①）
        BigDecimal total = dto.getItems().stream()
                .map(ReportItemDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 申请人身份只从登录上下文来（docs/12 §6）；deptId 由 JwtInterceptor 查库回填，为空说明身份不完整，直接拒绝
        if (userId == null || deptId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "当前用户缺少身份或部门，无法创建报销单");
        }
        ExpenseReport report = new ExpenseReport();
        report.setReportNo(generateReportNo());
        report.setApplicantId(userId);
        report.setApplicantDeptId(deptId);
        report.setTitle(dto.getTitle());
        report.setExpenseType(dto.getExpenseType());
        report.setAmountTotal(total);
        report.setCurrency("CNY");
        report.setStatus(STATUS_DRAFT);
        report.setCurrentStep(0);
        report.setTotalSteps(0);
        report.setRemark(dto.getRemark());
        reportMapper.insert(report);

        int sortNo = 1;
        for (ReportItemDTO itemDTO : dto.getItems()) {
            ExpenseItem item = new ExpenseItem();
            item.setReportId(report.getId());
            item.setExpenseDate(itemDTO.getExpenseDate());
            item.setCategory(itemDTO.getCategory());
            item.setAmount(itemDTO.getAmount());
            item.setDescription(itemDTO.getDescription());
            item.setInvoiceId(itemDTO.getInvoiceId());
            item.setSortNo(sortNo++);
            itemMapper.insert(item);
            // 发票先上传后建单：建单时绑定到本单，校验必须是本人上传且未绑定（docs/11 §3.4）
            if (itemDTO.getInvoiceId() != null) {
                invoiceService.bindReport(itemDTO.getInvoiceId(), report.getId(), userId);
            }
        }

        return getDetail(report.getId());
    }

    @Override
    public ReportDetailVO getDetail(Long id) {
        ExpenseReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }

        List<ExpenseItem> items = itemMapper.selectList(new LambdaQueryWrapper<ExpenseItem>()
                .eq(ExpenseItem::getReportId, id)
                .orderByAsc(ExpenseItem::getSortNo));

        ReportDetailVO vo = new ReportDetailVO();
        vo.setId(report.getId());
        vo.setReportNo(report.getReportNo());
        vo.setTitle(report.getTitle());
        vo.setExpenseType(report.getExpenseType());
        vo.setAmountTotal(report.getAmountTotal());
        vo.setCurrency(report.getCurrency());
        vo.setStatus(report.getStatus());
        vo.setCurrentStep(report.getCurrentStep());
        vo.setTotalSteps(report.getTotalSteps());
        vo.setRemark(report.getRemark());
        vo.setCreatedAt(report.getCreatedAt());
        // 可用动作只从状态机推导，前端不自行判断，避免两边规则漂移（docs/03 §3.1）。
        // SUBMIT/WITHDRAW 只属于申请人本人；无登录上下文（单测/内部调用）时不过滤、也不加 EDIT。
        CurrentUser current = UserContext.get();
        boolean applicant = current != null && current.userId().equals(report.getApplicantId());
        List<String> actions = new ArrayList<>(stateMachine.eventsOf(ReportStatus.valueOf(report.getStatus())).stream()
                .sorted()
                .map(ReportEvent::name)
                .filter(name -> current == null || applicant
                        || !"SUBMIT".equals(name) && !"WITHDRAW".equals(name))
                .toList());
        // 草稿的编辑按钮不在状态机事件表里，只在本人且草稿时追加
        if (applicant && STATUS_DRAFT.equals(report.getStatus())) {
            actions.add("EDIT");
        }
        vo.setAvailableActions(actions);
        vo.setItems(items.stream().map(this::toItemVO).toList());
        vo.setInvoices(invoiceService.listByReport(id));
        // AI 建议只读展示：查询失败也不影响详情与审批
        try {
            vo.setAiAdvice(aiReviewService.getAdvice(id));
        } catch (Exception e) {
            log.warn("读取 AI 建议失败 reportId={}", id, e);
        }
        return vo;
    }

    @Override
    public ReportDetailVO getDetailFor(Long id, Long userId, Long deptId, java.util.Set<String> roles) {
        ExpenseReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        if (!canViewDetail(report, userId, deptId, roles)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该报销单");
        }
        return getDetail(id);
    }

    /**
     * 详情可见范围（docs/03 §2.2「本人/审批人/财务」）：
     * 本人、该单任一审批人（含历史节点）、财务/管理员、同部门主管与分管。
     * 与 {@link #listReports} 的 scope 判定同源，避免"列表能看、详情 403"。
     */
    private boolean canViewDetail(ExpenseReport report, Long userId, Long deptId, java.util.Set<String> roles) {
        if (report.getApplicantId() != null && report.getApplicantId().equals(userId)) {
            return true;
        }
        if (hasAnyRole(roles, "FINANCE", "ADMIN")) {
            return true;
        }
        boolean sameDept = deptId != null && deptId.equals(report.getApplicantDeptId());
        if (sameDept && hasAnyRole(roles, "SUPERVISOR", "DIRECTOR")) {
            return true;
        }
        Long nodeCount = approvalMapper.selectCount(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, report.getId())
                .eq(ExpenseApproval::getApproverId, userId));
        return nodeCount != null && nodeCount > 0;
    }

    @Override
    public ReportDetailVO submitReport(Long id, Long operatorId, String idempotencyKey, String traceId) {
        ExpenseReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        // 归属校验必须在幂等回放之前：否则他人用别单的 Idempotency-Key 触发回放，会从 getDetail 拿到详情（docs/03 §2.2）
        if (operatorId == null || !operatorId.equals(report.getApplicantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有申请人本人可以提交该报销单");
        }

        // 幂等回放：已在审批中且 submit_token 与本次 Idempotency-Key 一致，直接返回详情，不重复写快照
        if (!STATUS_DRAFT.equals(report.getStatus())) {
            boolean inApproval = STATUS_PENDING.equals(report.getStatus())
                    || STATUS_APPROVING.equals(report.getStatus());
            if (inApproval && idempotencyKey != null && idempotencyKey.equals(report.getSubmitToken())) {
                return getDetail(id);
            }
            // 其余情况一律交给状态机判非法（终态提交、已在审批中换 key 提交都会抛 STATE_ILLEGAL）
            throwIllegalSubmit(report.getStatus());
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 Idempotency-Key");
        }

        // T1 守卫①：明细 ≥ 1、合计等于单据总额且大于 0
        ReportStatus target = stateMachine.transit(ReportStatus.DRAFT, ReportEvent.SUBMIT);
        List<ExpenseItem> items = itemMapper.selectList(new LambdaQueryWrapper<ExpenseItem>()
                .eq(ExpenseItem::getReportId, id));
        validateSubmitAmount(report, items);

        String trace = normalizeTraceId(traceId);
        try {
            return transactionTemplate.execute(status -> doSubmit(report, idempotencyKey, trace, target));
        } catch (DuplicateKeyException e) {
            // uk_submit_token 冲突：并发/重复提交。同一单据按幂等返回详情；不同单据说明幂等键被复用
            ExpenseReport existing = reportMapper.selectOne(new LambdaQueryWrapper<ExpenseReport>()
                    .eq(ExpenseReport::getSubmitToken, idempotencyKey));
            if (existing != null && existing.getId().equals(id)) {
                return getDetail(id);
            }
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key 已用于其他报销单，请更换后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDetailVO withdrawReport(Long id, Long operatorId, String traceId) {
        ExpenseReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        // T2 守卫①：操作人必须是申请人——防止任何人凭 id 撤回别人的单（docs/02 §3）
        if (operatorId == null || !operatorId.equals(report.getApplicantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能撤回本人的报销单");
        }

        // T2 守卫②：只有 PENDING 才能撤回，其余状态由状态机抛 STATE_ILLEGAL
        ReportStatus target = stateMachine.transit(ReportStatus.valueOf(report.getStatus()), ReportEvent.WITHDRAW);

        // T2 守卫①：该单不能有任何已动作的审批节点（TRANSFERRED/SKIPPED 也算动过）
        Long actedCount = approvalMapper.selectCount(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, id)
                .ne(ExpenseApproval::getStatus, STATUS_PENDING));
        if (actedCount != null && actedCount > 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "已有审批动作，不能撤回");
        }

        int updated = reportMapper.update(null, new LambdaUpdateWrapper<ExpenseReport>()
                .eq(ExpenseReport::getId, id)
                .eq(ExpenseReport::getStatus, STATUS_PENDING)
                .eq(ExpenseReport::getVersion, report.getVersion())
                .set(ExpenseReport::getStatus, target.name())
                .set(ExpenseReport::getCurrentStep, 0)
                .set(ExpenseReport::getSubmitToken, null)
                .set(ExpenseReport::getSubmittedAt, null)
                .setSql("version = version + 1"));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "报销单状态已变化，请刷新后重试");
        }

        int cancelled = approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, id)
                .eq(ExpenseApproval::getStatus, STATUS_PENDING)
                .set(ExpenseApproval::getStatus, NODE_CANCELLED)
                .setSql("version = version + 1"));

        // T2 副作用②：释放冻结（docs/06 §4.2）；用单据现有的 budget_id 定位，不按当前部门/月份重算
        budgetService.release(report);

        SysUser applicant = sysUserMapper.selectById(report.getApplicantId());
        writeStatusLog(id, normalizeTraceId(traceId), ACTION_WITHDRAW, STATUS_PENDING, target.name(),
                report.getApplicantId(), applicant == null ? null : applicant.getRealName(),
                "{\"cancelledApprovals\":" + cancelled + "}");
        return getDetail(id);
    }

    /**
     * 提交事务体：审批链快照 → 条件更新单据（乐观锁 + submit_token）→ 审计 → 回读详情。
     * 任何一步失败整体回滚，不会留下半条审批链。
     */
    private ReportDetailVO doSubmit(ExpenseReport report, String idempotencyKey, String traceId, ReportStatus target) {
        LocalDateTime now = LocalDateTime.now();
        SysUser applicant = sysUserMapper.selectById(report.getApplicantId());
        if (applicant == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批链不完整：申请人不存在");
        }

        List<ChainStep> chain = resolveApprovalChain(applicant, now);
        saveApprovalChain(report.getId(), chain);

        // T1 守卫③：冻结预算。先把提交时间和幂等 token 回填到实体，freeze 用它们算账期与 biz_no
        report.setSubmittedAt(now);
        report.setSubmitToken(idempotencyKey);
        DeptBudget budget = budgetService.freeze(report);

        int updated = reportMapper.update(null, new LambdaUpdateWrapper<ExpenseReport>()
                .eq(ExpenseReport::getId, report.getId())
                .eq(ExpenseReport::getStatus, STATUS_DRAFT)
                .eq(ExpenseReport::getVersion, report.getVersion())
                .set(ExpenseReport::getStatus, target.name())
                .set(ExpenseReport::getCurrentStep, 1)
                .set(ExpenseReport::getTotalSteps, chain.size())
                .set(ExpenseReport::getSubmitToken, idempotencyKey)
                .set(ExpenseReport::getSubmittedAt, now)
                .set(ExpenseReport::getBudgetId, budget.getId())
                .set(ExpenseReport::getBudgetPeriod, budget.getPeriod())
                .setSql("version = version + 1"));
        if (updated == 0) {
            // 条件更新失败即并发冲突，整个事务回滚（docs/02 §6）
            throw new BizException(ErrorCode.STATE_ILLEGAL, "报销单状态已变化，请刷新后重试");
        }

        // T1 副作用：写 AI 审核事件（与提交同事务，提交成功则消息必达）
        outboxService.append("REPORT_SUBMITTED", "REPORT", report.getId(),
                "{\"reportId\":" + report.getId()
                        + ",\"reportVersion\":" + (report.getVersion() == null ? 1 : report.getVersion() + 1)
                        + ",\"amountTotal\":\"" + (report.getAmountTotal() == null
                        ? "0.00" : report.getAmountTotal().toPlainString())
                        + "\",\"expenseType\":\"" + report.getExpenseType()
                        + "\",\"deptId\":" + report.getApplicantDeptId()
                        + ",\"submittedAt\":\"" + now.format(EVENT_TIME) + "\"}");

        writeStatusLog(report.getId(), traceId, ACTION_SUBMIT, STATUS_DRAFT, target.name(),
                applicant.getId(), applicant.getRealName(), buildChainDetailJson(chain));
        return getDetail(report.getId());
    }

    /**
     * 解析审批链：直属主管 → 财务（FINANCE 角色）→ 分管（DIRECTOR 角色）。
     * 每级 SINGLE 单签，deadline 按提交时间 + 各级 SLA 计算。
     * 缺任一级都视为数据不完整，直接拒绝提交，避免生成"走不完"的审批链。
     */
    private List<ChainStep> resolveApprovalChain(SysUser applicant, LocalDateTime now) {
        Long managerId = applicant.getManagerUserId();
        if (managerId == null) {
            throw incompleteChain("申请人[" + applicant.getRealName() + "]未配置直属主管");
        }
        SysUser supervisor = requireActiveUser(managerId, "直属主管");
        SysUser finance = requireRoleUser(ROLE_FINANCE, "财务");
        SysUser director = requireRoleUser(ROLE_DIRECTOR, "分管");

        return List.of(
                new ChainStep(1, "直属主管", supervisor, ROLE_SUPERVISOR, now.plusHours(SUPERVISOR_SLA_HOURS)),
                new ChainStep(2, "财务", finance, ROLE_FINANCE, now.plusHours(FINANCE_SLA_HOURS)),
                new ChainStep(3, "分管", director, ROLE_DIRECTOR, now.plusHours(DIRECTOR_SLA_HOURS)));
    }

    private SysUser requireActiveUser(Long userId, String stepLabel) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw incompleteChain(stepLabel + "不存在或已离职");
        }
        return user;
    }

    private SysUser requireRoleUser(String roleCode, String stepLabel) {
        SysUser user = sysUserMapper.selectFirstActiveByRoleCode(roleCode);
        if (user == null) {
            throw incompleteChain("缺少 " + roleCode + " 角色的在职" + stepLabel + "审批人");
        }
        return user;
    }

    private BizException incompleteChain(String reason) {
        return new BizException(ErrorCode.PARAM_ERROR, "审批链不完整：" + reason);
    }

    /**
     * 写审批链快照。撤回后重新提交时，库里同一 (report_id, step_no, approver_id) 的节点已存在
     * （唯一键 uk_report_step_approver 不区分状态），直接复用已作废节点并重置为 PENDING，
     * 既满足唯一约束，也保留该单的审批历史行。
     */
    private void saveApprovalChain(Long reportId, List<ChainStep> chain) {
        List<ExpenseApproval> reusableNodes = approvalMapper.selectList(new LambdaQueryWrapper<ExpenseApproval>()
                .eq(ExpenseApproval::getReportId, reportId)
                .in(ExpenseApproval::getStatus, NODE_CANCELLED, NODE_SKIPPED));

        for (ChainStep step : chain) {
            String deptName = deptNameOf(step.approver());
            ExpenseApproval reusable = reusableNodes.stream()
                    .filter(node -> Objects.equals(node.getStepNo(), step.stepNo())
                            && Objects.equals(node.getApproverId(), step.approver().getId()))
                    .findFirst()
                    .orElse(null);

            if (reusable == null) {
                ExpenseApproval node = new ExpenseApproval();
                node.setReportId(reportId);
                node.setStepNo(step.stepNo());
                node.setStepName(step.stepName());
                node.setApprovalMode(APPROVAL_MODE_SINGLE);
                node.setApproverId(step.approver().getId());
                node.setApproverName(step.approver().getRealName());
                node.setApproverDeptName(deptName);
                node.setSourceRole(step.sourceRole());
                node.setStatus(STATUS_PENDING);
                node.setDeadlineAt(step.deadlineAt());
                approvalMapper.insert(node);
                continue;
            }

            approvalMapper.update(null, new LambdaUpdateWrapper<ExpenseApproval>()
                    .eq(ExpenseApproval::getId, reusable.getId())
                    .set(ExpenseApproval::getStepName, step.stepName())
                    .set(ExpenseApproval::getApprovalMode, APPROVAL_MODE_SINGLE)
                    .set(ExpenseApproval::getApproverName, step.approver().getRealName())
                    .set(ExpenseApproval::getApproverDeptName, deptName)
                    .set(ExpenseApproval::getSourceRole, step.sourceRole())
                    .set(ExpenseApproval::getStatus, STATUS_PENDING)
                    .set(ExpenseApproval::getDeadlineAt, step.deadlineAt())
                    .set(ExpenseApproval::getActedAt, null)
                    .set(ExpenseApproval::getComment, null)
                    .setSql("version = version + 1"));
            reusableNodes.remove(reusable);
        }
    }

    /** 审批人部门名快照；部门缺失说明组织数据不完整，同样按审批链不完整处理 */
    private String deptNameOf(SysUser user) {
        SysDept dept = user.getDeptId() == null ? null : sysDeptMapper.selectById(user.getDeptId());
        if (dept == null || dept.getName() == null || dept.getName().isBlank()) {
            throw incompleteChain("审批人[" + user.getRealName() + "]所在部门不存在");
        }
        return dept.getName();
    }

    /** T1 守卫①：明细条数与金额一致性校验 */
    private void validateSubmitAmount(ExpenseReport report, List<ExpenseItem> items) {
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "报销明细不能为空，至少需要一条");
        }
        BigDecimal sum = items.stream()
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "报销金额合计必须大于 0");
        }
        if (report.getAmountTotal() == null || report.getAmountTotal().compareTo(sum) != 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "报销单总额与明细金额之和不一致");
        }
    }

    /** 非草稿状态重提时，用状态机抛出 STATE_ILLEGAL（失败尝试不落审计，docs/02 §8） */
    private void throwIllegalSubmit(String status) {
        stateMachine.transit(ReportStatus.valueOf(status), ReportEvent.SUBMIT);
        throw new BizException(ErrorCode.STATE_ILLEGAL, "状态 " + status + " 不允许提交");
    }

    private void writeStatusLog(Long reportId, String traceId, String action, String fromStatus, String toStatus,
                                Long operatorId, String operatorName, String detail) {
        ExpenseStatusLog log = new ExpenseStatusLog();
        log.setTraceId(traceId);
        log.setReportId(reportId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setDetail(detail);
        statusLogMapper.insert(log);
    }

    /** 审批链摘要 JSON，detail 列是 JSON 类型，这里手工拼装避免额外序列化依赖 */
    private String buildChainDetailJson(List<ChainStep> chain) {
        String steps = chain.stream()
                .map(step -> "{\"stepNo\":" + step.stepNo()
                        + ",\"approverId\":" + step.approver().getId()
                        + ",\"approverName\":\"" + escapeJson(step.approver().getRealName())
                        + "\",\"sourceRole\":\"" + step.sourceRole() + "\"}")
                .collect(Collectors.joining(","));
        return "{\"totalSteps\":" + chain.size() + ",\"steps\":[" + steps + "]}";
    }

    private String escapeJson(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 请求未带 X-Trace-Id 时生成 8 位短 trace，保证审计日志可按链路聚合 */
    private String normalizeTraceId(String traceId) {
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : traceId;
    }

    @Override
    public PageResult<ReportVO> listReports(Long userId, Long deptId, java.util.Set<String> roles,
                                            String scope, String status, String keyword, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String sc = scope == null || scope.isBlank() ? "mine" : scope;

        Long applicantId = null;
        Long deptFilter = null;
        switch (sc) {
            case "mine" -> applicantId = userId;
            case "dept" -> {
                if (!hasAnyRole(roles, "SUPERVISOR", "FINANCE", "DIRECTOR", "ADMIN")) {
                    throw new BizException(ErrorCode.FORBIDDEN, "无权查看部门单据");
                }
                deptFilter = deptId;
            }
            case "all" -> {
                if (!hasAnyRole(roles, "FINANCE", "ADMIN")) {
                    throw new BizException(ErrorCode.FORBIDDEN, "无权查看全部单据");
                }
            }
            default -> throw new BizException(ErrorCode.PARAM_ERROR, "scope 只能是 mine/dept/all");
        }

        long total = reportMapper.countPageList(applicantId, deptFilter, status, keyword);
        if (total == 0) {
            return PageResult.empty(p, s);
        }
        int offset = (p - 1) * s;
        List<ReportVO> records = reportMapper.selectPageList(applicantId, deptFilter, status, keyword, offset, s);
        return PageResult.of(records, total, p, s);
    }

    private boolean hasAnyRole(java.util.Set<String> roles, String... anyOf) {
        if (roles == null) {
            return false;
        }
        for (String role : anyOf) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 编辑草稿（PUT /reports/{id}）：只允许本人 + DRAFT。
     * 明细整体替换（先删后插），金额按新明细重算，不信任前端传的总额。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDetailVO updateDraft(Long id, ReportCreateDTO dto, Long operatorId) {
        ExpenseReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        if (operatorId == null || !operatorId.equals(report.getApplicantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能编辑本人的报销单");
        }
        if (!STATUS_DRAFT.equals(report.getStatus())) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "只有草稿可以编辑");
        }

        BigDecimal total = dto.getItems().stream()
                .map(ReportItemDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 条件更新：状态仍为 DRAFT 且版本未变，防止与提交/撤回并发
        int updated = reportMapper.update(null, new LambdaUpdateWrapper<ExpenseReport>()
                .eq(ExpenseReport::getId, id)
                .eq(ExpenseReport::getStatus, STATUS_DRAFT)
                .eq(ExpenseReport::getVersion, report.getVersion())
                .set(ExpenseReport::getTitle, dto.getTitle())
                .set(ExpenseReport::getExpenseType, dto.getExpenseType())
                .set(ExpenseReport::getAmountTotal, total)
                .set(ExpenseReport::getRemark, dto.getRemark())
                .setSql("version = version + 1"));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "报销单状态已变化，请刷新后重试");
        }

        // 草稿阶段没有审批历史依赖明细行，直接整体替换
        itemMapper.delete(new LambdaQueryWrapper<ExpenseItem>().eq(ExpenseItem::getReportId, id));
        int sortNo = 1;
        for (ReportItemDTO itemDTO : dto.getItems()) {
            ExpenseItem item = new ExpenseItem();
            item.setReportId(id);
            item.setExpenseDate(itemDTO.getExpenseDate());
            item.setCategory(itemDTO.getCategory());
            item.setAmount(itemDTO.getAmount());
            item.setDescription(itemDTO.getDescription());
            item.setInvoiceId(itemDTO.getInvoiceId());
            item.setSortNo(sortNo++);
            itemMapper.insert(item);
            if (itemDTO.getInvoiceId() != null) {
                invoiceService.bindReport(itemDTO.getInvoiceId(), id, operatorId);
            }
        }
        return getDetail(id);
    }

    /**
     * 删除草稿（DELETE /reports/{id}）。
     * 删除**不是**状态流转：不进状态机、不写审计（与 createDraft/updateDraft 一致），
     * 只把 deleted 置 1 并解绑发票；明细随单一起失去展示意义，物理删除。
     */
    @Override
    @Transactional
    public void deleteDraft(Long id, Long operatorId) {
        ExpenseReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        if (operatorId == null || !operatorId.equals(report.getApplicantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能删除本人的报销单");
        }
        if (!STATUS_DRAFT.equals(report.getStatus())) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "只有草稿可以删除");
        }
        // @TableLogic：delete 翻译成 UPDATE deleted=1，条件里同时带上归属与状态，防并发提交后误删
        int deleted = reportMapper.delete(new LambdaQueryWrapper<ExpenseReport>()
                .eq(ExpenseReport::getId, id)
                .eq(ExpenseReport::getApplicantId, operatorId)
                .eq(ExpenseReport::getStatus, STATUS_DRAFT));
        if (deleted == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "报销单状态已变化，请刷新后重试");
        }
        itemMapper.delete(new LambdaQueryWrapper<ExpenseItem>().eq(ExpenseItem::getReportId, id));
        invoiceService.unbindByReport(id);
    }

    @Override
    public List<TimelineVO> getTimeline(Long id) {
        ExpenseReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        return statusLogMapper.selectList(new LambdaQueryWrapper<ExpenseStatusLog>()
                        .eq(ExpenseStatusLog::getReportId, id)
                        .orderByAsc(ExpenseStatusLog::getId))
                .stream()
                .map(this::toTimelineVO)
                .toList();
    }

    private TimelineVO toTimelineVO(ExpenseStatusLog log) {
        TimelineVO vo = new TimelineVO();
        vo.setId(log.getId());
        vo.setTraceId(log.getTraceId());
        vo.setAction(log.getAction());
        vo.setFromStatus(log.getFromStatus());
        vo.setToStatus(log.getToStatus());
        vo.setOperatorId(log.getOperatorId());
        vo.setOperatorName(log.getOperatorName());
        vo.setDetail(log.getDetail());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }

    private ReportItemVO toItemVO(ExpenseItem item) {
        ReportItemVO vo = new ReportItemVO();
        vo.setId(item.getId());
        vo.setExpenseDate(item.getExpenseDate());
        vo.setCategory(item.getCategory());
        vo.setAmount(item.getAmount());
        vo.setDescription(item.getDescription());
        vo.setInvoiceId(item.getInvoiceId());
        return vo;
    }

    /** 单号格式：EXP + 秒级时间 + 3 位随机，唯一性由 uk_report_no 兜底。 */
    private String generateReportNo() {
        return "EXP" + LocalDateTime.now().format(REPORT_NO_TIME)
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    /** 审批链中的一级快照（内存态，落库后对应 expense_approval 一行） */
    private record ChainStep(int stepNo, String stepName, SysUser approver,
                             String sourceRole, LocalDateTime deadlineAt) {
    }
}
