package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.PaymentOrder;
import com.occ.smartexpense.vo.PaymentVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 付款单数据访问。一单一付由 uk_report 保证，重复消费插入时会抛 DuplicateKeyException。
 */
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    @Select("SELECT * FROM payment_order WHERE report_id = #{reportId}")
    PaymentOrder selectByReportId(@Param("reportId") Long reportId);

    /** 列表：按状态/收款人/单号过滤，联表取报销单号与标题（docs/03 §2.4） */
    @Select("""
            <script>
            SELECT p.id, p.payment_no, p.report_id, r.report_no, r.title,
                   p.payee_user_id, p.payee_name, p.amount, p.status, p.paid_at, p.created_at
            FROM payment_order p
            JOIN expense_report r ON r.id = p.report_id AND r.deleted = 0
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND p.status = #{status}
            </if>
            <if test="payeeUserId != null">
              AND p.payee_user_id = #{payeeUserId}
            </if>
            <if test="reportNo != null and reportNo != ''">
              AND r.report_no LIKE CONCAT('%', #{reportNo}, '%')
            </if>
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<PaymentVO> selectPage(@Param("status") String status,
                               @Param("payeeUserId") Long payeeUserId,
                               @Param("reportNo") String reportNo,
                               @Param("offset") int offset,
                               @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM payment_order p
            JOIN expense_report r ON r.id = p.report_id AND r.deleted = 0
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND p.status = #{status}
            </if>
            <if test="payeeUserId != null">
              AND p.payee_user_id = #{payeeUserId}
            </if>
            <if test="reportNo != null and reportNo != ''">
              AND r.report_no LIKE CONCAT('%', #{reportNo}, '%')
            </if>
            </script>
            """)
    long countPage(@Param("status") String status,
                   @Param("payeeUserId") Long payeeUserId,
                   @Param("reportNo") String reportNo);

    /** 单条详情，供确认后回显 */
    @Select("""
            SELECT p.id, p.payment_no, p.report_id, r.report_no, r.title,
                   p.payee_user_id, p.payee_name, p.amount, p.status, p.paid_at, p.created_at
            FROM payment_order p
            JOIN expense_report r ON r.id = p.report_id AND r.deleted = 0
            WHERE p.id = #{id}
            """)
    PaymentVO selectVOById(@Param("id") Long id);
}
