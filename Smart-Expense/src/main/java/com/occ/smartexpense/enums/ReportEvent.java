package com.occ.smartexpense.enums;

/**
 * 状态机事件，对应 docs/02-状态机.md §3 流转表（T1–T6）。
 */
public enum ReportEvent {

    SUBMIT, //提交
    WITHDRAW, //撤回
    APPROVE, //通过
    REJECT, //拒绝
    PAY_SUCCESS //已支付
}
