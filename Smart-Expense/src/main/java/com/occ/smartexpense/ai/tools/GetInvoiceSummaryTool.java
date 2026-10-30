package com.occ.smartexpense.ai.tools;

import com.occ.smartexpense.ai.AiTool;
import org.springframework.stereotype.Component;

/**
 * 工具：查单据关联发票摘要（docs/11 §5.3）。
 * 参数：{"reportId":1001}
 * 返回：{"invoices":[{"invoiceNo":"12345678","invoiceDate":"2026-10-20","amount":"1500.00","confidence":0.92,"duplicateCount":1}]}
 * 脱敏：不含图片 URL。
 * 依赖第 4 周发票链路（invoice 表 + 实体），当前骨架先占位。
 */
@Component
public class GetInvoiceSummaryTool implements AiTool {

    @Override
    public String name() {
        return "getInvoiceSummary";
    }

    @Override
    public String execute(String argsJson) {
        // TODO 第 4 周接入 InvoiceMapper；当前返回空数组，Agent 可正常继续
        return "{\"invoices\":[]}";
    }
}
