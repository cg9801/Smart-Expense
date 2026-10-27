package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.ApprovalActionDTO;
import com.occ.smartexpense.service.ApprovalService;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalService approvalService;

    @GetMapping("/pending")
    public Result<PageResult<ApprovalTodoVO>> listPending(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(approvalService.listPending(userId, page, size));
    }

    @PostMapping("/{approvalId}/approve")
    public Result<ReportDetailVO> approve(
            @PathVariable("approvalId") Long approvalId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Valid @RequestBody(required = false) ApprovalActionDTO body) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(approvalService.approve(approvalId, userId, body == null ? null : body.getComment()));
    }

    @PostMapping("/{approvalId}/reject")
    public Result<ReportDetailVO> reject(
            @PathVariable("approvalId") Long approvalId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Valid @RequestBody(required = false) ApprovalActionDTO body) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(approvalService.reject(approvalId, userId, body == null ? null : body.getComment()));
    }
}
