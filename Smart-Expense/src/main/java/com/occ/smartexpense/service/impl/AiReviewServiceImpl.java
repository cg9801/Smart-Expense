package com.occ.smartexpense.service.impl;

import com.occ.smartexpense.entity.AiReview;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.mapper.AiReviewMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.service.AiReviewService;
import com.occ.smartexpense.vo.AiAdviceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * AI 审批建议实现骨架，见 docs/11 §5。
 * 当前只实现读取与过期判定；Agent 运行逻辑留给 AgentLoop 实现后接入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private final AiReviewMapper aiReviewMapper;
    private final ExpenseReportMapper reportMapper;

    @Override
    public AiAdviceVO getAdvice(Long reportId) {
        AiReview review = aiReviewMapper.selectLatestByReportId(reportId);
        if (review == null) {
            return null;   // 审批页显示"暂无 AI 建议"，不影响审批
        }
        ExpenseReport report = reportMapper.selectById(reportId);
        AiAdviceVO vo = new AiAdviceVO();
        BeanUtils.copyProperties(review, vo);
        vo.setReasons(review.getAiReasons());
        vo.setStale(isStale(review, report));
        return vo;
    }

    @Override
    public void runAgent(Long reportId, Long reportVersion) {
        // TODO 1) 幂等：selectByReportVersion 已存在则直接返回
        // TODO 2) 组装初始上下文（docs/11 §5.3）
        // TODO 3) AgentLoop.run(context) → AgentResult
        // TODO 4) 校验输出（Schema + evidence 白名单）→ 风险合并（只能升）
        // TODO 5) 写 ai_review（uk_report_version 兜底）
        throw new UnsupportedOperationException("runAgent 待实现，见 docs/11 §5.2/§5.9");
    }

    /** 单据版本变了说明建议过期：撤回改单重提后旧建议不能再展示 */
    private boolean isStale(AiReview review, ExpenseReport report) {
        if (report == null || review.getReportVersion() == null || report.getVersion() == null) {
            return false;
        }
        return review.getReportVersion().longValue() != report.getVersion().longValue();
    }
}
