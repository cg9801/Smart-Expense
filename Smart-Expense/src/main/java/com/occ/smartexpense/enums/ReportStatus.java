package com.occ.smartexpense.enums;

/**
 * 报销单状态，取值与 docs/02-状态机.md §1 一一对应。
 */
public enum ReportStatus {

    DRAFT,//草稿
    PENDING,//待提交
    APPROVING,//审批中
    APPROVED,//通过
    REJECTED,//拒绝
    PAID;//已支付

    /** 终态不允许再有任何流转 */
    public boolean isTerminal() {
        return this == REJECTED || this == PAID;
    }
}
