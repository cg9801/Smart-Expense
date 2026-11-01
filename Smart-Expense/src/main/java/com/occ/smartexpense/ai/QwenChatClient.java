package com.occ.smartexpense.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼（DashScope）OpenAI 兼容客户端，见 docs/11 §5.9。
 *
 * <p>配置项（application.yaml / 环境变量）：
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

    private final RestClient restClient;
    private final String apiKey;
    private final String textModel;

    public QwenChatClient(@Value("${ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                          @Value("${ai.api-key:}") String apiKey,
                          @Value("${ai.text-model:qwen-plus}") String textModel,
                          @Value("${ai.agent.step-timeout-ms:3000}") long stepTimeoutMs) {
        this.apiKey = apiKey;
        this.textModel = textModel;
        // 单步超时由客户端负责，AgentLoop 只管总预算
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(stepTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(stepTimeoutMs));
        // 不注入 RestClient.Builder：本项目没有 webclient starter，拿不到自动配置的 Builder，
        // 一旦 ai.provider=qwen 就会启动失败（2026-10-31 实测）
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public String chat(List<Map<String, String>> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 ai.api-key，请设置环境变量 DASHSCOPE_API_KEY");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", textModel);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        // 强制 JSON 输出，降低"模型附带解释文字"导致解析失败的概率
        body.put("response_format", Map.of("type", "json_object"));

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("DashScope 返回空响应");
        }
        String content = response.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("DashScope 响应缺少 choices[0].message.content");
        }
        return content;
    }
}