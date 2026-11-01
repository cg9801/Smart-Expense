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

    /**
     * 我的已办（GET /approvals/handled）：我处理过的节点，按处理时间倒序。
     * TRANSFERRED 也算"我处理过"——它同样消耗了我的待办。
     */
    @Select("""
        SELECT COUNT(*)
        FROM expense_approval a
        JOIN expense_report r ON r.id = a.report_id
        WHERE a.approver_id = #{approverId}
          AND a.status IN ('APPROVED', 'REJECTED', 'TRANSFERRED')
          AND r.deleted = 0
        """)
    long countHandled(@Param("approverId") Long approverId);

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
               a.status,
               a.acted_at,
               a.comment
        FROM expense_approval a
        JOIN expense_report r ON r.id = a.report_id
        JOIN sys_user u ON u.id = r.applicant_id
        WHERE a.approver_id = #{approverId}
          AND a.status IN ('APPROVED', 'REJECTED', 'TRANSFERRED')
          AND r.deleted = 0
        ORDER BY a.acted_at DESC, a.id DESC
        LIMIT #{size} OFFSET #{offset}
        """)
    List<ApprovalTodoVO> selectHandled(@Param("approverId") Long approverId,
                                       @Param("offset") int offset,
                                       @Param("size") int size);

    /**
     * 超时扫描候选（docs/02 §7）：当前级、仍待处理、已过 deadline 的节点。
     * 只扫当前级，避免上一级遗留节点在推进后被误升级。
     */
    @Select("""
        SELECT a.*
        FROM expense_approval a
        JOIN expense_report r ON r.id = a.report_id
        WHERE a.status = 'PENDING'
          AND a.deadline_at IS NOT NULL
          AND a.deadline_at < NOW(3)
          AND r.deleted = 0
          AND r.status IN ('PENDING', 'APPROVING')
          AND a.step_no = r.current_step
        ORDER BY a.deadline_at ASC
        LIMIT 100
        """)
    List<ExpenseApproval> selectOverdue();
}
