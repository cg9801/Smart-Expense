package com.occ.smartexpense.ai.tools;

import com.occ.smartexpense.ai.AiTool;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具：查申请人最近相似单，用于识别重复报销/异常频率（docs/11 §5.3）。
 * 参数：{"applicantId":1,"days":30}
 * 返回：{"count":3,"avgAmount":"800.00","items":[{"reportNo":"EXP...","amount":"1500.00","date":"2026-10-20"}]}
 * 脱敏：不含审批意见原文，最多 10 条。
 */
@Component
@RequiredArgsConstructor
public class FindSimilarReportsTool implements AiTool {

    private final ExpenseReportMapper reportMapper;

    @Override
    public String name() {
        return "findSimilarReports";
    }

    @Override
    public String execute(String argsJson) {
        // TODO 校验 days <= 90 → 按 applicantId + 时间窗口查最近单 → 聚合 count/avg → 拼 JSON
        return "{\"error\":\"NOT_IMPLEMENTED\"}";
    }
}
