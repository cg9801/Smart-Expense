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
     * MQ 消费者入口：消费日志幂等 + 跑 Agent + 落库。
     * 与 runAgent 分开放，是为了把"消费日志 + 跑 Agent"放进同一个事务。
     */
    void consume(String msgId, String payloadJson);

    /**
     * 跑一次 Agent 并落库（幂等 uk_report_version）。
     *
     * @param reportId      单据 id
     * @param reportVersion 事件发生时的 version，用于过期判定与幂等
     */
    void runAgent(Long reportId, Long reportVersion);

    /**
     * 手动重算建议（POST /admin/ai-review/refresh，排障用）：
     * 删掉当前版本旧记录后重跑，绕过 runAgent 的"同版本幂等"。
     * 仍然只写 ai_review，不影响审批。
     *
     * @return 重算后的建议；模型失败时返回 null（与 getAdvice 一致）
     */
    AiAdviceVO refresh(Long reportId);
}
