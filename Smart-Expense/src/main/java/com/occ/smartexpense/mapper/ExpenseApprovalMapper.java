package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.ExpenseApproval;
import com.occ.smartexpense.vo.ApprovalTodoVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 审批链快照与审批任务数据访问。
 */
public interface ExpenseApprovalMapper extends BaseMapper<ExpenseApproval> {

    @Select("""
        SELECT COUNT(*)
        FROM expense_approval a
        JOIN expense_report r ON r.id = a.report_id
        WHERE a.approver_id = #{approverId}
          AND a.status = 'PENDING'
          AND r.deleted = 0
          AND r.status IN ('PENDING', 'APPROVING')
          AND a.step_no = r.current_step
        """)
    long countPending(@Param("approverId") Long approverId);
    @Select("""
        SELECT a.id           AS approval_id,
               r.id           AS report_id,
               r.report_no,
               r.title,
               u.real_name    AS applicant_name,
               r.amount_total,
               a.step_no,
               a.step_name,
               a.deadline_at,
               a.status
        FROM expense_approval a
        JOIN expense_report r ON r.id = a.report_id
        JOIN sys_user u ON u.id = r.applicant_id
        WHERE a.approver_id = #{approverId}
          AND a.status = 'PENDING'
          AND r.deleted = 0
          AND r.status IN ('PENDING', 'APPROVING')
          AND a.step_no = r.current_step
        ORDER BY a.deadline_at ASC
        LIMIT #{size} OFFSET #{offset}
        """)
    List<ApprovalTodoVO> selectPending(@Param("approverId") Long approverId,
                                       @Param("offset") int offset,
                                       @Param("size") int size);
}
