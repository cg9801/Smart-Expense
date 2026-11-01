package com.occ.smartexpense.service;

/**
 * 审批 SLA（docs/02-状态机.md §7）：超时提醒 + 自动升级。
 * 只动审批节点（换人），不动单据状态——因此不违反状态机约束。
 */
public interface ApprovalSlaService {

    /**
     * 扫描一次超时节点：未超 1 个 SLA 的写提醒，超过 1 个 SLA 的升级。
     *
     * @return 本次实际处理（提醒或升级）的节点数，便于定时任务打点
     */
    int scanOverdue();
}
