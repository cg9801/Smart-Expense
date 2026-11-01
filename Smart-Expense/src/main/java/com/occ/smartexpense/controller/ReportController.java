package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.security.CurrentUser;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.ReportService;
import com.occ.smartexpense.vo.ReportDetailVO;
import com.occ.smartexpense.vo.ReportVO;
import com.occ.smartexpense.vo.TimelineVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报销单接口：只做参数接收、校验、调用 Service、包装 Result。
 * 接口契约见 docs/03-接口清单.md §2.2。
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping
    public Result<ReportDetailVO> createDraft(@Valid @RequestBody ReportCreateDTO dto) {
        // 申请人身份只从登录上下文取，请求体里的同名字段一律无效（docs/12 §6）
        CurrentUser current = UserContext.requireUser();
        return Result.ok(reportService.createDraft(dto, current.userId(), current.deptId()));
    }

    @GetMapping("/{id}")
    public Result<ReportDetailVO> getDetail(@PathVariable Long id) {
        // 详情含金额、明细与审批链：先登录，再按「本人/审批人/财务/同部门主管」鉴权（docs/03 §2.2）
        CurrentUser current = UserContext.requireUser();
        return Result.ok(reportService.getDetailFor(id, current.userId(), current.deptId(), current.roles()));
    }

    /**
     * 分页列表（docs/03 §2.2）：scope=mine|dept|all、status、keyword。
     * scope 的权限判定在 Service 层（dept 要主管+，all 要财务/管理员），前端传什么都不越权。
     */
    @GetMapping
    public Result<PageResult<ReportVO>> listReports(
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        CurrentUser current = UserContext.requireUser();
        return Result.ok(reportService.listReports(current.userId(), current.deptId(), current.roles(),
                scope, status, keyword, page, size));
    }

    /** 编辑草稿（docs/03 §2.2）：仅本人 + DRAFT，明细整体替换 */
    @PutMapping("/{id}")
    public Result<ReportDetailVO> updateDraft(@PathVariable Long id, @Valid @RequestBody ReportCreateDTO dto) {
        Long operatorId = UserContext.requireUserId();
        return Result.ok(reportService.updateDraft(id, dto, operatorId));
    }

    /** 审批轨迹（docs/03 §2.2）：审计日志回放，按时间正序 */
    @GetMapping("/{id}/timeline")
    public Result<List<TimelineVO>> getTimeline(@PathVariable Long id) {
        UserContext.requireUserId();
        return Result.ok(reportService.getTimeline(id));
    }

    /** 删除草稿（docs/03 §2.2）：逻辑删除，仅本人 + DRAFT；不是状态流转，不进状态机 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteDraft(@PathVariable Long id) {
        reportService.deleteDraft(id, UserContext.requireUserId());
        return Result.ok();
    }

    /**
     * 提交报销单，接口契约见 docs/03-接口清单.md §2.2。
     * Idempotency-Key 缺失时由 Service 抛 PARAM_ERROR（不依赖前端校验）。
     */
    @PostMapping("/{id}/submit")
    public Result<ReportDetailVO> submitReport(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        // 提交会冻结预算、生成审批链：归属校验在 Service 内做，操作人只从上下文取（docs/03 §2.2）
        Long operatorId = UserContext.requireUserId();
        return Result.ok(reportService.submitReport(id, operatorId, idempotencyKey, traceId));
    }

    /** 撤回报销单：仅申请人本人、PENDING 且无人审批时允许，见 docs/02-状态机.md T2。 */
    @PostMapping("/{id}/withdraw")
    public Result<ReportDetailVO> withdrawReport(
            @PathVariable Long id,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        // 身份从上下文取，不信任路径/参数：否则任何人凭 id 就能撤别人的单（docs/02 §3 T2 守卫①）
        Long operatorId = UserContext.requireUserId();
        return Result.ok(reportService.withdrawReport(id, operatorId, traceId));
    }
}
