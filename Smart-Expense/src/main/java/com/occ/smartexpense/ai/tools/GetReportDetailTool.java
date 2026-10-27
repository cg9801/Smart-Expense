package com.occ.smartexpense.ai.tools;

import com.occ.smartexpense.ai.AiTool;
import com.occ.smartexpense.mapper.ExpenseItemMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具：查单据明细（docs/11 §5.3）。
 * 参数：{"reportId":1001}
 * 返回：{"reportId":1001,"amountTotal":"1500.00","items":[{"lineNo":1,"category":"办公用品","summary":"打印纸","amount":"1500.00"}]}
 * 脱敏：不含申请人手机号/身份证。
 */
@Component
@RequiredArgsConstructor
public class GetReportDetailTool implements AiTool {

    private final ExpenseReportMapper reportMapper;
    private final ExpenseItemMapper itemMapper;

    @Override
    public String name() {
        return "getReportDetail";
    }

    @Override
    public String execute(String argsJson) {
        // TODO 解析 reportId → 查单据 + 明细 → 拼 JSON（截断 10 行/2KB）
        return "{\"error\":\"NOT_IMPLEMENTED\"}";
    }
}
