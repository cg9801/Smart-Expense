package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.BudgetPool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 总预算池 Mapper：按月唯一定位（uk_period） */
public interface BudgetPoolMapper extends BaseMapper<BudgetPool> {

    @Select("SELECT * FROM budget_pool WHERE period = #{period}")
    BudgetPool selectByPeriod(@Param("period") String period);
}
