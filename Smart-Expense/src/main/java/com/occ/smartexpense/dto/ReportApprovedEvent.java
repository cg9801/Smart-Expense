package com.occ.smartexpense.dto;

public record ReportApprovedEvent(
        Long reportId,
        String reportNo,
        Long applicantId,
        String applicantName,
        String amountTotal,     // 事件里是字符串，避免浮点误差
        String currency,
        String approvedAt) {
}