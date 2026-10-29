package com.occ.smartexpense.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 模型：不调外部服务，按固定脚本返回，用于单测与压测（docs/11 §5.9）。
 *
 * <p>脚本约定：
 * <ol>
 *   <li>第一次调用返回"请求工具"的 JSON；</li>
 *   <li>第二次调用返回最终建议 JSON。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiChatClient implements AiChatClient {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public String chat(List<Map<String, String>> messages) {
        if (calls.getAndIncrement() == 0) {
            // 第一步：模拟模型"想查预算"
            return """
                    {"tool":"getBudget","args":{"deptId":2,"period":"2026-10"}}
                    """;
        }
        // 第二步：给最终建议（AgentLoop 从 final 字段取值）
        return """
                {"final":{"riskLevel":"LOW","confidence":0.9,
                "reasons":[{"code":"MOCK","text":"mock 模型固定输出","evidence":"hardFindings#0"}]}}
                """;
    }
}
