package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.BudgetTxn;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 预算流水数据访问（只追加，不做 update/delete）。
 */
public interface BudgetTxnMapper extends BaseMapper<BudgetTxn> {

    /** 按幂等键查流水，用于判断"这个动作是否已经记过账" */
    @Select("SELECT * FROM budget_txn WHERE biz_no = #{bizNo}")
    BudgetTxn selectByBizNo(@Param("bizNo") String bizNo);
}
