package com.occ.smartexpense.ai.tools;

import com.occ.smartexpense.ai.AiTool;
import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.mapper.DeptBudgetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具：查部门预算（docs/11 §5.3）。
 * 参数：{"deptId":2,"period":"2026-10"}
 * 返回：{"total":"50000.00","used":"3000.00","frozen":"500.00","available":"46500.00"}
 */
@Component
@RequiredArgsConstructor
public class GetBudgetTool implements AiTool {

    private final DeptBudgetMapper deptBudgetMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "getBudget";
    }

    @Override
    public String execute(String argsJson) {
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            long deptId = args.path("deptId").asLong(0);
            String period = args.path("period").asText("");
            if (deptId <= 0 || !period.matches("\\d{4}-\\d{2}")) {
                return "{\"error\":\"BAD_ARGS: deptId and period(YYYY-MM) required\"}";
            }
            short fiscalYear = (short) Integer.parseInt(period.substring(0, 4));
            DeptBudget budget = deptBudgetMapper.selectByDeptPeriod(deptId, fiscalYear, period);
            if (budget == null) {
                return "{\"error\":\"NOT_FOUND\"}";
            }

            BigDecimal available = budget.getTotalAmount()
                    .subtract(budget.getUsedAmount())
                    .subtract(budget.getFrozenAmount());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", budget.getTotalAmount().toPlainString());
            result.put("used", budget.getUsedAmount().toPlainString());
            result.put("frozen", budget.getFrozenAmount().toPlainString());
            result.put("available", available.toPlainString());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"INTERNAL\"}";
        }
    }
}
