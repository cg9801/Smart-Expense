package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.vo.BudgetVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    /** 某账期全部部门预算（管理端总览/预算管理），联表带部门名（docs/03 §2.4） */
    @Select("""
        SELECT b.dept_id, d.name AS dept_name, b.period,
               b.total_amount, b.used_amount, b.frozen_amount
        FROM dept_budget b
        JOIN sys_dept d ON d.id = b.dept_id
        WHERE b.period = #{period}
        ORDER BY b.dept_id
        """)
    List<BudgetVO> selectBudgetList(@Param("period") String period);
}
