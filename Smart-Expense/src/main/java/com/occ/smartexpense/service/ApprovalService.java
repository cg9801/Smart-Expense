package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;

public interface ApprovalService {

    PageResult<ApprovalTodoVO> listPending(Long approverId, int page, int size);

    /** 旧签名：不带 AI 建议表态，等价于 aiReviewId=null */
    default ReportDetailVO approve(Long approvalId, Long userId, String comment) {
        return approve(approvalId, userId, comment, null, null);
    }

    ReportDetailVO approve(Long approvalId, Long userId, String comment, Long aiReviewId, Boolean aiAdopted);

    default ReportDetailVO reject(Long approvalId, Long userId, String comment) {
        return reject(approvalId, userId, comment, null, null);
    }

    ReportDetailVO reject(Long approvalId, Long userId, String comment, Long aiReviewId, Boolean aiAdopted);
}
