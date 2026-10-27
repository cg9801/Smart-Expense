package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.security.RequireRole;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.ReconcileService;
import com.occ.smartexpense.vo.ReconcileDiffVO;
import com.occ.smartexpense.vo.ReconcileRecordVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 对账管理接口，见 docs/03 §2.6 与 docs/12 §8。
 * 财务/管理员可见。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/reconcile")
@RequireRole({"ADMIN", "FINANCE"})
public class AdminReconcileController {

    private final ReconcileService reconcileService;

    /** 手动触发，bizDate 默认 T-1 */
    @PostMapping("/run")
    public Result<ReconcileRecordVO> run(
            @RequestParam(value = "bizDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        UserContext.requireUserId();
        LocalDate target = bizDate == null ? LocalDate.now().minusDays(1) : bizDate;
        return Result.ok(reconcileService.run(target));
    }

    @GetMapping("/records")
    public Result<PageResult<ReconcileRecordVO>> listRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        UserContext.requireUserId();
        return Result.ok(reconcileService.listRecords(page, size));
    }

    @GetMapping("/diffs")
    public Result<PageResult<ReconcileDiffVO>> listDiffs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String diffType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        UserContext.requireUserId();
        return Result.ok(reconcileService.listDiffs(status, diffType, bizDate, page, size));
    }

    /** 人工修复：只允许 OPEN → REPAIRED */
    @PostMapping("/diffs/{id}/repair")
    public Result<Void> repair(@PathVariable("id") Long id) {
        reconcileService.repair(id, UserContext.requireUserId());
        return Result.ok();
    }
}
