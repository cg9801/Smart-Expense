package com.occ.smartexpense.ai.tools;

import com.occ.smartexpense.ai.AiTool;
import com.occ.smartexpense.mapper.DeptBudgetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具：查部门预算（docs/11 §5.3）。
 * 参数：{"deptId":2,"period":"2026-10"}
 * 返回：{"total":"50000.00","used":"3000.00","frozen":"500.00","available":"46500.00"}
 */
@Component
@RequiredArgsConstructor
public class GetBudgetTool implements AiTool {

    private final DeptBudgetMapper deptBudgetMapper;

    @Override
    public String name() {
        return "getBudget";
    }

    @Override
    public String execute(String argsJson) {
        // TODO 校验 period 格式（YYYY-MM）→ selectByDeptPeriod → 拼 JSON
        return "{\"error\":\"NOT_IMPLEMENTED\"}";
    }
}
