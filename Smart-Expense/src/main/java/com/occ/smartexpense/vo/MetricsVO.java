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
}
