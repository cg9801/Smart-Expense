package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.ApprovalActionDTO;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.ApprovalService;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 审批接口，身份统一从 UserContext 取（docs/12 §6）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    @GetMapping("/pending")
    public Result<PageResult<ApprovalTodoVO>> listPending(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = UserContext.requireUserId();
        return Result.ok(approvalService.listPending(userId, page, size));
    }

    @PostMapping("/{approvalId}/approve")
    public Result<ReportDetailVO> approve(
            @PathVariable("approvalId") Long approvalId,
            @Valid @RequestBody(required = false) ApprovalActionDTO body) {
        Long userId = UserContext.requireUserId();
        return Result.ok(approvalService.approve(approvalId, userId,
                body == null ? null : body.getComment(),
                body == null ? null : body.getAiReviewId(),
                body == null ? null : body.getAiAdopted()));
    }

    @PostMapping("/{approvalId}/reject")
    public Result<ReportDetailVO> reject(
            @PathVariable("approvalId") Long approvalId,
            @Valid @RequestBody(required = false) ApprovalActionDTO body) {
        Long userId = UserContext.requireUserId();
        return Result.ok(approvalService.reject(approvalId, userId,
                body == null ? null : body.getComment(),
                body == null ? null : body.getAiReviewId(),
                body == null ? null : body.getAiAdopted()));
    }
}
