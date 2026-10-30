package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.ai.AgentLoop;
import com.occ.smartexpense.ai.AiAdviceValidator;
import com.occ.smartexpense.entity.AiReview;
import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.ExpenseItem;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.mapper.AiReviewMapper;
import com.occ.smartexpense.mapper.DeptBudgetMapper;
import com.occ.smartexpense.mapper.ExpenseItemMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.MqConsumeLogMapper;
import com.occ.smartexpense.rule.HardFinding;
import com.occ.smartexpense.rule.HardRuleEngine;
import com.occ.smartexpense.rule.RiskMerger;
import com.occ.smartexpense.service.AiReviewService;
import com.occ.smartexpense.vo.AiAdviceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 审批建议实现，见 docs/11-AI识别与辅助审核.md §5。
 *
 * <p>只写 ai_review；Agent 失败、超时、非法 JSON 都只影响本表，不影响审批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private static final String CONSUMER = "ai-review";
    /** 低于该置信度的建议不展示：宁可不给建议，也不误导审批人（docs/11 §5.4） */
    private static final BigDecimal MIN_DISPLAY_CONFIDENCE = new BigDecimal("0.60");
    private final AiAdviceValidator adviceValidator;
    private final HardRuleEngine hardRuleEngine;
    private final RiskMerger riskMerger;
    private final AiReviewMapper aiReviewMapper;
    private final ExpenseReportMapper reportMapper;
    private final ExpenseItemMapper itemMapper;
    private final DeptBudgetMapper deptBudgetMapper;
    private final MqConsumeLogMapper consumeLogMapper;
    private final AgentLoop agentLoop;
    private final ObjectMapper objectMapper;

    @Value("${ai.provider:mock}")
    private String provider;

    @Value("${ai.text-model:qwen-plus}")
    private String textModel;

    @Override
    public AiAdviceVO getAdvice(Long reportId) {
        AiReview review = aiReviewMapper.selectLatestByReportId(reportId);
        if (review == null) {
            return null;   // 审批页显示"暂无 AI 建议"，不影响审批
        }
        if (!"SUCCESS".equals(review.getStatus())) {
            return null;
        }
        // 低置信建议不展示：必须判 null（列允许为空），比较只能用 compareTo
        if (review.getConfidence() != null
                && review.getConfidence().compareTo(MIN_DISPLAY_CONFIDENCE) < 0) {
            return null;
        }
        ExpenseReport report = reportMapper.selectById(reportId);
        AiAdviceVO vo = new AiAdviceVO();
        BeanUtils.copyProperties(review, vo);
        vo.setReasons(review.getAiReasons());
        vo.setStale(isStale(review, report));
        return vo;
    }

    /**
     * 消费入口：消费日志与 Agent 落库同一事务，失败整体回滚，消息重投时可重新处理。
     */
    @Override
    @Transactional
    public void consume(String msgId, String payloadJson) {
        try {
            consumeLogMapper.insert(msgId, CONSUMER);
        } catch (DuplicateKeyException e) {
            log.info("AI 审核消息重复，跳过 msgId={}", msgId);
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            long reportId = payload.path("reportId").asLong(0);
            long reportVersion = payload.path("reportVersion").asLong(0);
            if (reportId <= 0 || reportVersion <= 0) {
                log.warn("AI 审核事件缺少字段，丢弃 payload={}", payloadJson);
                return;
            }
            runAgent(reportId, reportVersion);
        } catch (Exception e) {
            // 消费异常不往外抛：AI 失败不能进死信再重试，也不能阻塞业务
            log.warn("AI 审核处理失败 msgId={}", msgId, e);
        }
    }

    @Override
    @Transactional
    public void runAgent(Long reportId, Long reportVersion) {
        if (aiReviewMapper.selectByReportVersion(reportId, reportVersion) != null) {
            return;   // 同一版本已算过，幂等
        }
        ExpenseReport report = reportMapper.selectById(reportId);
        if (report == null) {
            return;
        }

        // 硬规则先算：它不依赖模型，模型挂了 findings 依然有效（docs/11 §2.1）
        List<ExpenseItem> items = itemMapper.selectList(new LambdaQueryWrapper<ExpenseItem>()
                .eq(ExpenseItem::getReportId, reportId)
                .orderByAsc(ExpenseItem::getSortNo));
        DeptBudget budget = report.getBudgetId() == null
                ? null : deptBudgetMapper.selectById(report.getBudgetId());
        List<HardFinding> findings = hardRuleEngine.evaluate(report, items, budget);

        AgentLoop.AgentResult result;
        try {
            result = agentLoop.run(buildContext(report, findings));
        } catch (Exception e) {
            log.warn("Agent 执行异常 reportId={}", reportId, e);
            result = new AgentLoop.AgentResult(null, 0, 0, 0, "AGENT_ERROR", 0, Set.of());
        }

        AiReview review = new AiReview();
        review.setReportId(reportId);
        review.setReportVersion(reportVersion);
        review.setModel("mock".equalsIgnoreCase(provider) ? "mock" : textModel);
        review.setAgentSteps(result.steps());
        review.setToolCalls(result.toolCalls());
        review.setToolErrors(result.toolErrors());
        review.setLatencyMs(Math.toIntExact(result.latencyMs()));
        // 硬规则结果独立于 AI 成败，始终落库（docs/11 §2.1）
        review.setHardFindings(writeFindings(findings));
        applyResult(review, result, findings);

        try {
            aiReviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            log.info("AI 建议已存在，忽略 reportId={}, version={}", reportId, reportVersion);
        }
    }

    /** 把 Agent 结果写到实体上：成功解析 final JSON，失败记 error */
    private void applyResult(AiReview review, AgentLoop.AgentResult result, List<HardFinding> findings) {
        if (!result.success()) {
            fail(review, result.error());
            return;
        }
        try {
            AiAdviceValidator.Verdict verdict = adviceValidator.validate(result.finalJson(), result.usedTools());
            if (!verdict.valid()) {
                log.info("AI 建议未通过校验 reportId={}, code={}", review.getReportId(), verdict.errorCode());
                fail(review, verdict.errorCode());
                return;
            }
            // 风险只能升不能降：最终 = max(硬规则, AI)，见 docs/11 §5.5
            review.setRiskLevel(riskMerger.merge(findings, verdict.riskLevel()));
            review.setConfidence(verdict.confidence());
            review.setAiReasons(verdict.reasonsJson());
            review.setStatus("SUCCESS");
        } catch (Exception e) {
            log.warn("AI 建议校验异常 reportId={}", review.getReportId(), e);
            fail(review, "VALIDATOR_ERROR");
        }
    }

    /** 初始上下文：只给最小字段，其余由 Agent 调工具获取（docs/11 §5.3） */
    private String buildContext(ExpenseReport report, List<HardFinding> findings) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("reportId", report.getId());
        context.put("amountTotal", report.getAmountTotal() == null
                ? "0.00" : report.getAmountTotal().toPlainString());
        context.put("expenseType", report.getExpenseType());
        context.put("deptId", report.getApplicantDeptId());
        context.put("period", periodOf(report));
        context.put("hardFindings", findings);
        return objectMapper.writeValueAsString(context);
    }

    /** 硬规则结果统一序列化口径：进上下文与落库用同一份 JSON */
    private String writeFindings(List<HardFinding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (Exception e) {
            log.warn("硬规则序列化失败，按空数组落库", e);
            return "[]";
        }
    }

    private String periodOf(ExpenseReport report) {
        if (report.getBudgetPeriod() != null && !report.getBudgetPeriod().isBlank()) {
            return report.getBudgetPeriod();
        }
        return LocalDate.now().toString().substring(0, 7);
    }

    /** 单据版本变了说明建议过期：撤回改单重提后旧建议不能再展示 */
    private boolean isStale(AiReview review, ExpenseReport report) {
        if (report == null || review.getReportVersion() == null || report.getVersion() == null) {
            return false;
        }
        return review.getReportVersion().longValue() != report.getVersion().longValue();
    }

    private void fail(AiReview review, String errorCode) {
        review.setStatus("FAILED");
        review.setErrorMsg(errorCode);
        review.setRiskLevel("LOW");
    }
}
