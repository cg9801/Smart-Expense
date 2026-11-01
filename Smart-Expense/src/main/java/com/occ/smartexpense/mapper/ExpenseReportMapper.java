package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.vo.ReportVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 报销单数据访问：简单 CRUD 用 BaseMapper，复杂查询写在 resources/mapper/*.xml。
 */
public interface ExpenseReportMapper extends BaseMapper<ExpenseReport> {

    /**
     * 平均审批耗时（分钟），供 /admin/metrics 使用（docs/10 §4）。
     * 只统计已出结果的单，且在 Java 里再除以 60 转小时，避免 SQL 小数除法精度问题。
     * 没有匹配行时 AVG 返回 null，调用方必须判空。
     */
    @Select("""
            SELECT AVG(TIMESTAMPDIFF(MINUTE, submitted_at, finished_at))
            FROM expense_report
            WHERE deleted = 0
              AND submitted_at IS NOT NULL
              AND finished_at IS NOT NULL
              AND status IN ('APPROVED','PAID','REJECTED')
            """)
    Double avgApprovalMinutes();

    /**
     * 列表分页（docs/03 §2.2 GET /reports）。
     * scope 的权限判定在 Service 层完成，这里只接收换算后的过滤条件：两个都为 null 即 scope=all。
     */
    @Select("""
            <script>
            SELECT id, report_no, title, expense_type, amount_total, status, created_at
            FROM expense_report
            WHERE deleted = 0
            <if test="applicantId != null">AND applicant_id = #{applicantId}</if>
            <if test="deptId != null">AND applicant_dept_id = #{deptId}</if>
            <if test="status != null and status != ''">AND status = #{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (title LIKE CONCAT('%', #{keyword}, '%') OR report_no LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY created_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<ReportVO> selectPageList(@Param("applicantId") Long applicantId,
                                  @Param("deptId") Long deptId,
                                  @Param("status") String status,
                                  @Param("keyword") String keyword,
                                  @Param("offset") int offset,
                                  @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM expense_report
            WHERE deleted = 0
            <if test="applicantId != null">AND applicant_id = #{applicantId}</if>
            <if test="deptId != null">AND applicant_dept_id = #{deptId}</if>
            <if test="status != null and status != ''">AND status = #{status}</if>
            <if test="keyword != null and keyword != ''">
              AND (title LIKE CONCAT('%', #{keyword}, '%') OR report_no LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countPageList(@Param("applicantId") Long applicantId,
                       @Param("deptId") Long deptId,
                       @Param("status") String status,
                       @Param("keyword") String keyword);
}
