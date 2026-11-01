package com.occ.smartexpense.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Mock 模型：不调外部服务，按固定脚本返回，用于单测与压测（docs/11 §5.9）。
 *
 * <p>脚本按对话状态推进，而不是按调用计数：看到 {@code TOOL_RESULT} 才给最终结论，
 * 否则请求工具。这样同一进程里重复 runAgent（refresh/测试）也能各自走完完整循环，
 * 不会出现"第二次直接给 final、但 evidence 引用的工具这轮根本没调用"被校验剔除。
 */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiChatClient implements AiChatClient {

    @Override
    public String chat(List<Map<String, String>> messages) {
        boolean hasToolResult = messages.stream().anyMatch(m ->
                "user".equals(m.get("role"))
                        && m.getOrDefault("content", "").startsWith("TOOL_RESULT"));
        if (!hasToolResult) {
            // 第一步：模拟模型"想查预算"
            return """
                    {"tool":"getBudget","args":{"deptId":2,"period":"2026-10"}}
                    """;
        }
        // 第二步：给最终建议（AgentLoop 从 final 字段取值）
        // evidence 指向"整份工具结果"（不带 # 路径），避免路径在空数组上越界被白名单剔除；
        // 真实模型会给更细的路径（如 getReportDetail#items.0.amount）
        return """
                {"final":{"riskLevel":"LOW","confidence":0.86,
                "reasons":[
                  {"code":"AMOUNT_CONSISTENT","text":"明细金额合计与单据金额一致，未发现金额不符","evidence":"getBudget"},
                  {"code":"BUDGET_OK","text":"部门当月预算可用额度充足，未触发超预算规则","evidence":"getBudget"}
                ]}}
                """;
    }
}
