package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.ReconcileDiff;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账差异数据访问。三个过滤条件都可选，供 docs/03 §2.6 的差异列表接口使用。
 */
public interface ReconcileDiffMapper extends BaseMapper<ReconcileDiff> {

    @Select("""
            <script>
            SELECT * FROM reconcile_diff
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            <if test="diffType != null and diffType != ''">
              AND diff_type = #{diffType}
            </if>
            <if test="bizDate != null">
              AND biz_date = #{bizDate}
            </if>
            ORDER BY id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<ReconcileDiff> selectPage(@Param("status") String status,
                                   @Param("diffType") String diffType,
                                   @Param("bizDate") LocalDate bizDate,
                                   @Param("offset") int offset,
                                   @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*) FROM reconcile_diff
            WHERE 1 = 1
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            <if test="diffType != null and diffType != ''">
              AND diff_type = #{diffType}
            </if>
            <if test="bizDate != null">
              AND biz_date = #{bizDate}
            </if>
            </script>
            """)
    long countPage(@Param("status") String status,
                   @Param("diffType") String diffType,
                   @Param("bizDate") LocalDate bizDate);
}
