package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 状态流转审计日志数据访问（只追加）。
 */
public interface ExpenseStatusLogMapper extends BaseMapper<ExpenseStatusLog> {
    @Select("SELECT * FROM expense_approval\n" +
            "    WHERE report_id=#{report_id} AND step_no=#{step_no} FOR UPDATE")
    void lock(@Param("report_id") Long reportId, @Param("step_no") Integer stepNo);
}
