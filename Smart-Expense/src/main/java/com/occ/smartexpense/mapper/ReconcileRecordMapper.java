package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.ReconcileRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 对账批次数据访问。uk_biz_date 防重复批次，因此按 biz_date 查单条即可。
 */
public interface ReconcileRecordMapper extends BaseMapper<ReconcileRecord> {

    @Select("SELECT * FROM reconcile_record WHERE biz_date = #{bizDate}")
    ReconcileRecord selectByBizDate(@Param("bizDate") java.time.LocalDate bizDate);
}
