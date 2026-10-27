package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.AiReview;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 审批建议数据访问。uk_report_version 保证同一 (report_id, report_version) 只有一条。
 */
public interface AiReviewMapper extends BaseMapper<AiReview> {

    @Select("SELECT * FROM ai_review WHERE report_id = #{reportId} AND report_version = #{reportVersion}")
    AiReview selectByReportVersion(@Param("reportId") Long reportId,
                                   @Param("reportVersion") Long reportVersion);

    @Select("SELECT * FROM ai_review WHERE report_id = #{reportId} ORDER BY id DESC LIMIT 1")
    AiReview selectLatestByReportId(@Param("reportId") Long reportId);
}
