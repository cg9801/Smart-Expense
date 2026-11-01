package com.occ.smartexpense.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 运营指标出参，口径见 docs/10-指标与监控.md §3。
 * 比率和时长用 BigDecimal，避免前端拿到浮点误差。
 */
@Data
public class MetricsVO {

    /** 累计提交单数（submit_token 非空） */
    private long submitCount;

    /** 已通过单数（APPROVED + PAID） */
    private long approvedCount;

    /** 已驳回单数 */
    private long rejectedCount;

    /** 通过率 = approved / (approved + rejected)，保留 2 位小数 */
    private BigDecimal approveRate;

    /** 平均审批耗时（小时），保留 1 位小数 */
    private BigDecimal avgApprovalHours;

    /** 待付款单数（payment_order.status = CREATED） */
    private long paymentPending;

    /** 待处理对账差异数（reconcile_diff.status = OPEN） */
    private long reconcileOpenDiffs;

    /** 投递放弃的消息数（outbox_message.status = DEAD） */
    private long outboxDead;

    /** AI 建议生成成功率 = status=SUCCESS ÷ 总建议行数，保留 2 位小数（docs/11 §9） */
    private BigDecimal aiSuccessRate;

    /** AI 建议采纳率 = adopted=1 ÷ adopted IS NOT NULL，保留 2 位小数；未表态不算忽略 */
    private BigDecimal aiAdoptRate;

    /** AI 建议平均延迟（毫秒），保留 1 位小数 */
    private BigDecimal aiAvgLatencyMs;

    /** Agent 平均步数，保留 1 位小数；越高说明取证越复杂、越费钱 */
    private BigDecimal aiAvgSteps;

    /** 工具调用成功率 = (tool_calls - tool_errors) ÷ tool_calls，保留 2 位小数 */
    private BigDecimal aiToolSuccessRate;

    // ===== 发票识别指标（docs/19 §2）=====

    /** 发票总数（含未识别） */
    private long invoiceTotal;

    /** 识别成功率 = SUCCESS ÷ invoiceTotal */
    private BigDecimal invoiceSuccessRate;

    /** 人工复核率 = MANUAL ÷ invoiceTotal（主实现质量的直接体现） */
    private BigDecimal invoiceManualRate;

    /** 降级率 = degraded=1 ÷ invoiceTotal（主实现可用率的反面） */
    private BigDecimal invoiceDegradedRate;

    /** 识别耗时 P95（毫秒），保留 1 位小数 */
    private BigDecimal invoiceP95LatencyMs;

}
