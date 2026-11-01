package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.ApprovalActionDTO;
import com.occ.smartexpense.dto.TransferDTO;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.ApprovalService;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import com.occ.smartexpense.vo.TransferCandidateVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /** 我的已办（docs/03 §2.3）：我处理过的节点，按处理时间倒序 */
    @GetMapping("/handled")
    public Result<PageResult<ApprovalTodoVO>> listHandled(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = UserContext.requireUserId();
        return Result.ok(approvalService.listHandled(userId, page, size));
    }

    /**
     * 转交候选人下拉（docs/03 §2.3）：在职、非本人、非本单历史审批人。
     * reportId 可选，传了就顺带做防环过滤。
     */
    @GetMapping("/transfer-candidates")
    public Result<List<TransferCandidateVO>> listTransferCandidates(
            @RequestParam(required = false) Long reportId) {
        Long userId = UserContext.requireUserId();
        return Result.ok(approvalService.listTransferCandidates(userId, reportId));
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

    /** 转交：把待办转给同级另一人，不改变单据状态（docs/02 §5、docs/03 §2.3）。 */
    @PostMapping("/{approvalId}/transfer")
    public Result<ReportDetailVO> transfer(
            @PathVariable("approvalId") Long approvalId,
            @Valid @RequestBody TransferDTO body) {
        Long userId = UserContext.requireUserId();
        return Result.ok(approvalService.transfer(approvalId, userId, body.getTargetUserId(), body.getComment()));
    }
}
