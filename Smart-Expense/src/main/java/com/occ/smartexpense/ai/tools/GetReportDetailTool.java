package com.occ.smartexpense.ai.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.ai.AiTool;
import com.occ.smartexpense.entity.ExpenseItem;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.mapper.ExpenseItemMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查单据明细（docs/11 §5.3）。
 * 参数：{"reportId":1001}
 * 返回：{"reportId":1001,"reportNo":"EXP...","amountTotal":"1500.00","expenseType":"TRAVEL",
 *       "items":[{"lineNo":1,"category":"办公用品","summary":"打印纸","amount":"1500.00"}]}
 * 脱敏：不含申请人手机号/身份证。
 */
@Component
@RequiredArgsConstructor
public class GetReportDetailTool implements AiTool {

    private final ExpenseReportMapper reportMapper;
    private final ExpenseItemMapper itemMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "getReportDetail";
    }

    @Override
    public String execute(String argsJson) {
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            long reportId = args.path("reportId").asLong(0);
            if (reportId <= 0) {
                return "{\"error\":\"BAD_ARGS: reportId required\"}";
            }
            ExpenseReport report = reportMapper.selectById(reportId);
            if (report == null) {
                return "{\"error\":\"NOT_FOUND\"}";
            }
            List<ExpenseItem> items = itemMapper.selectList(new LambdaQueryWrapper<ExpenseItem>()
                    .eq(ExpenseItem::getReportId, reportId)
                    .orderByAsc(ExpenseItem::getSortNo)
                    .last("LIMIT 10"));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reportId", report.getId());
            result.put("reportNo", report.getReportNo());
            // 金额一律字符串，避免模型看到的数值精度与前端不一致
            result.put("amountTotal", report.getAmountTotal() == null
                    ? "0.00" : report.getAmountTotal().toPlainString());
            result.put("expenseType", report.getExpenseType());

            List<Map<String, Object>> itemRows = new ArrayList<>();
            int index = 1;
            for (ExpenseItem item : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("lineNo", item.getSortNo() == null ? index : item.getSortNo());
                row.put("category", item.getCategory());
                row.put("summary", item.getDescription());
                row.put("amount", item.getAmount() == null ? "0.00" : item.getAmount().toPlainString());
                itemRows.add(row);
                index++;
            }
            result.put("items", itemRows);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"INTERNAL\"}";
        }
    }
}
