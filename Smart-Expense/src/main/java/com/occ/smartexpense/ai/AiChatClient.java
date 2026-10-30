package com.occ.smartexpense.ai;

import java.util.List;
import java.util.Map;

/**
 * 聊天模型抽象，见 docs/11 §5.9。
 * 消息格式与 OpenAI 兼容接口一致：{@code [{role: system|user|assistant, content: "..."}]}。
 * 实现：MockAiChatClient（测试/压测）、QwenChatClient（DashScope OpenAI 兼容模式）。
 */
public interface AiChatClient {

    /**
     * 一次模型调用。
     *
     * @param messages 对话消息（已脱敏）
     * @return 模型原始输出（期望是 JSON 字符串，但不保证，由 AgentLoop 校验）
     */
    String chat(List<Map<String, String>> messages);
}
