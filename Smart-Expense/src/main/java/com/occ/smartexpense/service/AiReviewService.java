package com.occ.smartexpense.service;

import com.occ.smartexpense.vo.AiAdviceVO;

/**
 * AI 审批建议服务，见 docs/11-AI识别与辅助审核.md §5。
 * 只写 ai_review，不写业务表、不阻断审批。
 */
public interface AiReviewService {

    /** 审批详情用：取最新建议，report.version 不一致时标记 stale */
    AiAdviceVO getAdvice(Long reportId);

    /**
     * 异步消费入口：跑 Agent 并落库（幂等 uk_report_version）。
     *
     * @param reportId      单据 id
     * @param reportVersion 事件发生时的 version，用于过期判定与幂等
     */
    void runAgent(Long reportId, Long reportVersion);
}
