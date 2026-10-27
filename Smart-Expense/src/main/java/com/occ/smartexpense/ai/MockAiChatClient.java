package com.occ.smartexpense.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Mock 模型：不调外部服务，按固定脚本返回，用于单测与压测（docs/11 §5.9）。
 *
 * <p>脚本约定：
 * <ol>
 *   <li>第一次调用返回"请求工具"的 JSON；</li>
 *   <li>第二次调用返回最终建议 JSON。</li>
 * </ol>
 * 当前骨架先固定返回最终建议，等 AgentLoop 实现后再补工具调用剧本。
 */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiChatClient implements AiChatClient {

    @Override
    public String chat(List<Map<String, String>> messages) {
        // TODO 实现"工具调用 → 最终建议"的两步脚本，供 AgentLoop 单测使用
        return """
                {"final":{"riskLevel":"LOW","confidence":0.9,
                "reasons":[{"code":"MOCK","text":"mock 模型固定输出","evidence":"hardFindings#0"}]}}
                """;
    }
}
