package com.occ.smartexpense.ai;

import com.occ.smartexpense.ai.tools.FindSimilarReportsTool;
import com.occ.smartexpense.ai.tools.GetBudgetTool;
import com.occ.smartexpense.ai.tools.GetInvoiceSummaryTool;
import com.occ.smartexpense.ai.tools.GetPolicyTool;
import com.occ.smartexpense.ai.tools.GetReportDetailTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 循环骨架，见 docs/11-AI识别与辅助审核.md §5.9。
 *
 * <p>约束（实现时必须遵守）：
 * <ul>
 *   <li>模型请求 <= {@code ai.agent.max-steps}（默认 5）；</li>
 *   <li>总耗时 <= {@code ai.agent.total-timeout-ms}（默认 8000）；</li>
 *   <li>同一工具 + 相同参数只执行一次，第二次返回缓存；</li>
 *   <li>未知工具返回 {"error":"UNKNOWN_TOOL"}，不抛异常；</li>
 *   <li>非法 JSON 带错误信息重试 1 次，仍失败则返回 FAILED。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final List<AiTool> tools;
    private final AiChatClient aiChatClient;

    /** Agent 运行结果，供 AiReviewService 落库 */
    public record AgentResult(String finalJson, int steps, int toolCalls, int toolErrors, String error) {

        public boolean success() {
            return error == null && finalJson != null;
        }
    }

    /**
     * 执行一次 Agent 取证。
     *
     * @param initialContext 最小上下文 JSON（reportId/amountTotal/expenseType/deptId/period/hardFindings）
     */
    public AgentResult run(String initialContext) {
        // TODO 1) 组装 system prompt（工具清单 + 输出格式）
        // TODO 2) 循环：模型返回 {tool,args} → 执行工具 → 回填结果；返回 {final} → 结束
        // TODO 3) 步数/总超时/重复调用/未知工具/非法 JSON 按 docs/11 §5.9 处理
        // TODO 4) 返回 AgentResult(finalJson, steps, toolCalls, toolErrors, error)
        throw new UnsupportedOperationException("AgentLoop.run 待实现，见 docs/11 §5.9");
    }

    /** 工具名 → 工具实例，便于按模型返回的名字分发 */
    private Map<String, AiTool> toolMap() {
        Map<String, AiTool> map = new LinkedHashMap<>();
        for (AiTool tool : tools) {
            map.put(tool.name(), tool);
        }
        return map;
    }
}
