package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import com.occ.smartexpense.vo.TransferCandidateVO;

import java.util.List;

public interface ApprovalService {

    PageResult<ApprovalTodoVO> listPending(Long approverId, int page, int size);

    /** 我的已办（GET /approvals/handled）：APPROVED/REJECTED/TRANSFERRED，按处理时间倒序 */
    PageResult<ApprovalTodoVO> listHandled(Long approverId, int page, int size);

    /**
     * 转交候选人：在职、非本人、非本单历史审批人。
     * 只给下拉用，真正的防环仍在 transfer 里校验。
     */
    List<TransferCandidateVO> listTransferCandidates(Long selfId, Long reportId);

    /** 旧签名：不带 AI 建议表态，等价于 aiReviewId=null */
    default ReportDetailVO approve(Long approvalId, Long userId, String comment) {
        return approve(approvalId, userId, comment, null, null);
    }

    ReportDetailVO approve(Long approvalId, Long userId, String comment, Long aiReviewId, Boolean aiAdopted);

    default ReportDetailVO reject(Long approvalId, Long userId, String comment) {
        return reject(approvalId, userId, comment, null, null);
    }

    ReportDetailVO reject(Long approvalId, Long userId, String comment, Long aiReviewId, Boolean aiAdopted);

    /**
     * 转交审批任务（docs/02 §5）：原节点 PENDING → TRANSFERRED，插入同 step_no 的新 PENDING 节点。
     * 目标不得为本单历史审批人（防环）；转交不改变单据状态。
     *
     * @param approvalId   原审批节点 id
     * @param userId       当前操作人（必须是该节点审批人）
     * @param targetUserId 转交目标用户
     * @param comment      转交说明（选填）
     */
    ReportDetailVO transfer(Long approvalId, Long userId, Long targetUserId, String comment);
}
