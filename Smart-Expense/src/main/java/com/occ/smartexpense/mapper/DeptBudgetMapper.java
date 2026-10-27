package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.DeptBudget;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 部门预算数据访问。余额变更用条件更新（LambdaUpdateWrapper），不在此处写 UPDATE SQL。
 */
public interface DeptBudgetMapper extends BaseMapper<DeptBudget> {

    /** 按"部门 + 财年 + 账期"定位唯一预算行（uk_dept_period） */
    @Select("""
        SELECT *
        FROM dept_budget
        WHERE dept_id = #{deptId}
          AND fiscal_year = #{fiscalYear}
          AND period = #{period}
        """)
    DeptBudget selectByDeptPeriod(@Param("deptId") Long deptId,
                                  @Param("fiscalYear") Short fiscalYear,
                                  @Param("period") String period);
}
