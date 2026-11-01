package com.occ.smartexpense.rule;

/**
 * 发票类硬规则需要的事实，由调用方查好后传入（引擎保持纯函数，docs/19 §4）。
 *
 * @param invoiceCount               本单关联的发票数
 * @param duplicateFileOtherUploaders 同 file_hash 被其他上传人上传的次数
 * @param duplicateInvoiceNoReuse     同 (invoice_code, invoice_no) 被其他报销单使用的次数
 */
public record InvoiceFacts(int invoiceCount,
                           int duplicateFileOtherUploaders,
                           int duplicateInvoiceNoReuse) {
}
