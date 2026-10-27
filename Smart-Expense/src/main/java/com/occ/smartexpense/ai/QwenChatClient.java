package com.occ.smartexpense.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼（DashScope）OpenAI 兼容客户端，见 docs/11 §5.9。
 *
 * <p>配置项（application.yaml）：
 * <pre>
 * ai.provider=qwen
 * ai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
 * ai.api-key=${DASHSCOPE_API_KEY:}
 * ai.text-model=qwen-plus
 * </pre>
 * API Key 只从环境变量读取，禁止写进仓库。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "qwen")
public class QwenChatClient implements AiChatClient {

    @Value("${ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.text-model:qwen-plus}")
    private String textModel;

    @Override
    public String chat(List<Map<String, String>> messages) {
        // TODO 用 Spring RestClient 调 /chat/completions：
        //  - Authorization: Bearer ${ai.api-key}
        //  - body: {model, messages, response_format: {type: json_object}, temperature: 0.2}
        //  - 单次超时 3s（ai.agent.step-timeout-ms），失败抛异常由 AgentLoop 降级
        //  - apiKey 为空时直接抛 IllegalStateException，提示配置 DASHSCOPE_API_KEY
        throw new UnsupportedOperationException("QwenChatClient 待实现，见 docs/11 §5.9");
    }
}
