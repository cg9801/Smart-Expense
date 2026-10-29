package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.AiReview;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * AI 审批建议数据访问。uk_report_version 保证同一 (report_id, report_version) 只有一条。
 */
public interface AiReviewMapper extends BaseMapper<AiReview> {

    @Select("SELECT * FROM ai_review WHERE report_id = #{reportId} AND report_version = #{reportVersion}")
    AiReview selectByReportVersion(@Param("reportId") Long reportId,
                                   @Param("reportVersion") Long reportVersion);

    @Select("SELECT * FROM ai_review WHERE report_id = #{reportId} ORDER BY id DESC LIMIT 1")
    AiReview selectLatestByReportId(@Param("reportId") Long reportId);

    /**
     * AI 指标一次聚合，口径见 docs/11 §9 / docs/10 §3。
     *
     * <p>用条件聚合而不是 4 条 COUNT：只扫一遍表，且这些值来自同一快照，
     * 不会出现"成功数和采纳数取自不同时刻"的漂移。空表时 SUM/AVG 返回 NULL，由调用方兜底。
     *
     * <p>返回 Map 而不是实体：这是聚合结果，没有对应的表行。
     */
    @Select("""
            SELECT COUNT(*)                                             AS total_cnt,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END)  AS success_cnt,
                   SUM(CASE WHEN adopted = 1 THEN 1 ELSE 0 END)         AS adopted_cnt,
                   SUM(CASE WHEN adopted IS NOT NULL THEN 1 ELSE 0 END) AS adopted_total,
                   AVG(latency_ms)                                      AS avg_latency_ms,
                   AVG(agent_steps)                                     AS avg_steps
            FROM ai_review
            """)
    Map<String, Object> selectAiMetrics();


}
