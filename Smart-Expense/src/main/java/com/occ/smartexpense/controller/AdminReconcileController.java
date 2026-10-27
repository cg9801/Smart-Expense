package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.service.ReconcileService;
import com.occ.smartexpense.vo.ReconcileDiffVO;
import com.occ.smartexpense.vo.ReconcileRecordVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 对账管理接口，见 docs/03 §2.6。
 * 现阶段只认 X-User-Id；JWT 与 ADMIN/FINANCE 角色校验在第 5 周补。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/reconcile")
public class AdminReconcileController {

    private final ReconcileService reconcileService;

    /** 手动触发，bizDate 默认 T-1 */
    @PostMapping("/run")
    public Result<ReconcileRecordVO> run(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(value = "bizDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        LocalDate target = bizDate == null ? LocalDate.now().minusDays(1) : bizDate;
        return Result.ok(reconcileService.run(target));
    }

    @GetMapping("/records")
    public Result<PageResult<ReconcileRecordVO>> listRecords(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(reconcileService.listRecords(page, size));
    }

    @GetMapping("/diffs")
    public Result<PageResult<ReconcileDiffVO>> listDiffs(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String diffType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(reconcileService.listDiffs(status, diffType, bizDate, page, size));
    }

    /** 人工修复：只允许 OPEN → REPAIRED */
    @PostMapping("/diffs/{id}/repair")
    public Result<Void> repair(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        reconcileService.repair(id, userId);
        return Result.ok();
    }
}
