package com.occ.smartexpense.ai.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.ai.AiTool;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：查申请人最近相似单，用于识别重复报销/异常频率（docs/11 §5.3）。
 * 参数：{"applicantId":1,"days":30}（days 上限 90）
 * 返回：{"count":3,"avgAmount":"800.00","items":[{"reportNo":"EXP...","amount":"1500.00","date":"2026-10-20"}]}
 * 脱敏：不含审批意见原文，最多 10 条。
 */
@Component
@RequiredArgsConstructor
public class FindSimilarReportsTool implements AiTool {

    private static final int MAX_DAYS = 90;
    private static final int MAX_ROWS = 10;

    private final ExpenseReportMapper reportMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "findSimilarReports";
    }

    @Override
    public String execute(String argsJson) {
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            long applicantId = args.path("applicantId").asLong(0);
            int days = args.path("days").asInt(30);
            if (applicantId <= 0) {
                return "{\"error\":\"BAD_ARGS: applicantId required\"}";
            }
            if (days < 1 || days > MAX_DAYS) {
                return "{\"error\":\"BAD_ARGS: days must be 1..90\"}";
            }

            LocalDateTime from = LocalDateTime.now().minusDays(days);
            List<ExpenseReport> recent = reportMapper.selectList(new LambdaQueryWrapper<ExpenseReport>()
                    .eq(ExpenseReport::getApplicantId, applicantId)
                    .isNotNull(ExpenseReport::getSubmittedAt)
                    .ge(ExpenseReport::getSubmittedAt, from)
                    .orderByDesc(ExpenseReport::getSubmittedAt)
                    .last("LIMIT " + MAX_ROWS));

            BigDecimal sum = BigDecimal.ZERO;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ExpenseReport report : recent) {
                BigDecimal amount = report.getAmountTotal() == null ? BigDecimal.ZERO : report.getAmountTotal();
                sum = sum.add(amount);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("reportNo", report.getReportNo());
                row.put("amount", amount.toPlainString());
                row.put("date", report.getSubmittedAt().toLocalDate().toString());
                rows.add(row);
            }
            BigDecimal avg = recent.isEmpty() ? BigDecimal.ZERO
                    : sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_UP);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", recent.size());
            result.put("avgAmount", avg.toPlainString());
            result.put("items", rows);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"INTERNAL\"}";
        }
    }
}
