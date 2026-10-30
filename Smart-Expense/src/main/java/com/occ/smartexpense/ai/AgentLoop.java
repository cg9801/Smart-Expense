package com.occ.smartexpense.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Agent 循环，见 docs/11-AI识别与辅助审核.md §5.9。
 *
 * <p>协议（自定义 JSON，不依赖模型原生 function call）：
 * <ul>
 *   <li>模型要工具：{@code {"tool":"getBudget","args":{...}}}</li>
 *   <li>模型给结论：{@code {"final":{"riskLevel":"LOW","reasons":[...]}}}</li>
 * </ul>
 *
 * <p>约束：步数 <= maxSteps、总耗时 <= totalTimeoutMs、同工具同参数只执行一次、
 * 未知工具不中断、非法 JSON 重试一次、工具异常返回 INTERNAL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final String SYSTEM_PROMPT = """
            你是报销单审批助手。你只能通过工具获取信息，禁止编造任何事实。
            可用工具（全部只读）：
            - getReportDet  ail {"reportId": long}
            - getBudget {"deptId": long, "period": "YYYY-MM"}
            - findSimilarReports {"applicantId": long, "days": int}
            - getInvoiceSummary {"reportId": long}
            - getPolicy {"expenseType": string}
            输出规则：只输出一个 JSON，不要解释文字。
            要调用工具时：{"tool":"<name>","args":{...}}
            可以结束时：{"final":{"riskLevel":"LOW|MEDIUM|HIGH","confidence":0.0-1.0,
              "reasons":[{"code":"...","text":"...","evidence":"<工具名>#<字段路径>"}]}}
            evidence 必须来自工具返回结果，不能编造。
            """;

    private final List<AiTool> tools;
    private final AiChatClient aiChatClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.agent.max-steps:5}")
    private int maxSteps = 5;

    @Value("${ai.agent.total-timeout-ms:8000}")
    private long totalTimeoutMs = 8000;

    /** Agent 运行结果，供 AiReviewService 落库 */
    public record AgentResult(String finalJson, int steps, int toolCalls, int toolErrors, String error, long latencyMs,
                              Map<String, String> toolResults) {

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
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        long startNanos = System.nanoTime();
        Map<String, AiTool> toolMap = toolMap();
        Map<String, String> cache = new HashMap<>();
        // 工具名 -> 最后一次返回的 JSON：供校验层核对 evidence 路径是否真实存在
        // 同一工具多次调用时只保留最后一次（模型下结论通常依据最近一次查询）
        Map<String, String> toolResults = new LinkedHashMap<>();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", initialContext));

        int steps = 0;
        int toolCalls = 0;
        int toolErrors = 0;
        boolean jsonRetried = false;

        while (steps < maxSteps) {
            if (System.currentTimeMillis() > deadline) {
                return new AgentResult(null, steps, toolCalls, toolErrors, "TIMEOUT",elapsedMs(startNanos),Map.copyOf(toolResults));
            }

            String reply;
            try {
                reply = aiChatClient.chat(messages);
            } catch (Exception e) {
                log.warn("模型调用失败", e);
                return new AgentResult(null, steps, toolCalls, toolErrors, "MODEL_ERROR",elapsedMs(startNanos),Map.copyOf(toolResults));
            }
            steps++;

            JsonNode node;
            try {
                node = objectMapper.readTree(reply);
            } catch (Exception e) {
                if (!jsonRetried) {
                    jsonRetried = true;
                    messages.add(message("user", "输出不是合法 JSON，请只输出一个 JSON 对象。"));
                    continue;
                }
                return new AgentResult(null, steps, toolCalls, toolErrors, "BAD_JSON",elapsedMs(startNanos),Map.copyOf(toolResults));
            }

            if (node.has("final")) {
                return new AgentResult(node.get("final").toString(), steps, toolCalls, toolErrors, null,elapsedMs(startNanos),Map.copyOf(toolResults));
            }

            String toolName = node.path("tool").asText("");
            String args = node.has("args") ? node.get("args").toString() : "{}";
            AiTool tool = toolMap.get(toolName);
            if (tool == null) {
                toolErrors++;
                messages.add(message("assistant", reply));
                messages.add(message("user", "{\"error\":\"UNKNOWN_TOOL\"}"));
                continue;
            }

            String cacheKey = toolName + "|" + args;
            String result = cache.get(cacheKey);
            boolean hit = result != null;
            if (!hit) {
                try {
                    result = tool.execute(args);
                } catch (Exception e) {
                    log.warn("工具执行失败 tool={}", toolName, e);
                    result = "{\"error\":\"INTERNAL\"}";
                }
                if (result.contains("\"error\"")) {
                    toolErrors++;
                }
                cache.put(cacheKey, result);
                toolCalls++;
            }

            messages.add(message("assistant", reply));
            messages.add(message("user", "TOOL_RESULT " + result));
            // 缓存命中也算"见过"：模型拿到的确实是真实结果
            toolResults.put(toolName, result);
            if (hit) {
                log.debug("工具结果命中缓存 tool={}", toolName);
            }
        }
        return new AgentResult(null, steps, toolCalls, toolErrors, "STEP_LIMIT",elapsedMs(startNanos),Map.copyOf(toolResults));
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /** 工具名 → 工具实例，便于按模型返回的名字分发 */
    private Map<String, AiTool> toolMap() {
        Map<String, AiTool> map = new LinkedHashMap<>();
        for (AiTool tool : tools) {
            map.put(tool.name(), tool);
        }
        return map;
    }
    private long elapsedMs(long startNanos){
        long now = System.nanoTime();
        return (now -startNanos)/1_000_000;
    }
}
