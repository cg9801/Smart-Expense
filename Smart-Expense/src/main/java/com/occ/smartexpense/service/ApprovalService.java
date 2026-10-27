package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import com.occ.smartexpense.vo.ReportDetailVO;

public interface ApprovalService {
    PageResult<ApprovalTodoVO> listPending(Long approverId, int page, int size);

    ReportDetailVO approve(Long approvalId, Long userId, String comment);

    ReportDetailVO reject(Long approvalId, Long userId, String comment);
}
