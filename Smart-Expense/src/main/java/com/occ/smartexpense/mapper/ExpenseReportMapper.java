package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.ExpenseReport;
import org.apache.ibatis.annotations.Select;

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
}
