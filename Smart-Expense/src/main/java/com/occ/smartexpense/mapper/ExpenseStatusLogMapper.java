package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 状态流转审计日志数据访问（只追加）。
 */
public interface ExpenseStatusLogMapper extends BaseMapper<ExpenseStatusLog> {
    @Select("SELECT * FROM expense_approval\n" +
            "    WHERE report_id=#{report_id} AND step_no=#{step_no} FOR UPDATE")
    void lock(@Param("report_id") Long reportId, @Param("step_no") Integer stepNo);

    /**
     * 超时提醒去重（docs/02 §7）：同一节点在窗口内已提醒过就不再写。
     * 用 JSON 函数取值，别用 LIKE——JSON 列取出来会带空格（"approvalId": 12），拼字符串对不上。
     */
    @Select("""
            SELECT COUNT(*)
              FROM expense_status_log
             WHERE report_id = #{reportId}
               AND action = 'TIMEOUT_REMIND'
               AND CAST(JSON_UNQUOTE(JSON_EXTRACT(detail, '$.approvalId')) AS UNSIGNED) = #{approvalId}
               AND created_at > #{since}
            """)
    long countRemind(@Param("reportId") Long reportId,
                     @Param("approvalId") Long approvalId,
                     @Param("since") LocalDateTime since);
}
