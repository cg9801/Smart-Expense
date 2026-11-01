package com.occ.smartexpense.invoice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 识别链（docs/11 §3.2）：按配置顺序尝试 primary → fallback，全失败才算失败。
 * 降级过就标记 degraded=1，方便统计"主实现真实可用率"。
 */
@Slf4j
@Component
public class InvoiceRecognizerChain {

    private final List<InvoiceRecognizer> recognizers;
    private final String primary;
    private final String fallback;

    public InvoiceRecognizerChain(List<InvoiceRecognizer> recognizers,
                                  @Value("${app.invoice.recognizer.primary:mock}") String primary,
                                  @Value("${app.invoice.recognizer.fallback:}") String fallback) {
        this.recognizers = recognizers;
        this.primary = primary;
        this.fallback = fallback;
    }

    /**
     * 启动时打印识别器配置：用户"设了环境变量却还是 mock"时，先看这行日志。
     * 若列表里没有 qwen-vl，说明 qwen-enabled 没生效（进程环境没拿到变量）。
     */
    @jakarta.annotation.PostConstruct
    void logConfiguration() {
        List<String> names = recognizers.stream().map(InvoiceRecognizer::name).toList();
        log.info("发票识别链：primary={}, fallback={}, 已注册实现={}", primary, fallback, names);
        // 直接打进程环境变量：能一眼区分"IDEA 没注入"还是"配置名写错"
        log.info("识别相关环境变量：INVOICE_QWEN_ENABLED={}, INVOICE_RECOGNIZER_PRIMARY={}, DASHSCOPE_API_KEY={}",
                env("INVOICE_QWEN_ENABLED"), env("INVOICE_RECOGNIZER_PRIMARY"),
                System.getenv("DASHSCOPE_API_KEY") == null ? "未设置" : "已设置（不打印值）");
        if ("qwen-vl".equals(primary) && !names.contains("qwen-vl")) {
            log.warn("primary=qwen-vl 但 qwen-vl 未注册：检查启动进程的环境变量 "
                    + "INVOICE_QWEN_ENABLED=true 与 DASHSCOPE_API_KEY（IDEA 启动需在 Run Configuration 里配）");
        }
        if ("mock".equals(primary)) {
            log.info("当前主识别器是 mock：金额/日期为演示数据，接真实模型见 docs/18");
        }
    }

    /** 供排障接口使用：当前识别器链配置（管理员可查） */
    public java.util.Map<String, Object> describe() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("primary", primary);
        map.put("fallback", fallback);
        map.put("registered", recognizers.stream().map(InvoiceRecognizer::name).toList());
        map.put("envQwenEnabled", env("INVOICE_QWEN_ENABLED"));
        map.put("envPrimary", env("INVOICE_RECOGNIZER_PRIMARY"));
        map.put("apiKeyConfigured", System.getenv("DASHSCOPE_API_KEY") != null
                && !System.getenv("DASHSCOPE_API_KEY").isBlank());
        return map;
    }

    private String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? "(未设置)" : value;
    }

    /**
     * 按配置顺序识别；任何一个成功就返回。
     *
     * @throws RecognitionException 所有可用实现都失败
     */
    public ChainResult recognize(String filePath, String contentType) throws RecognitionException {
        List<InvoiceRecognizer> order = resolveOrder();
        if (order.isEmpty()) {
            throw new RecognitionException("没有可用的识别器：检查 app.invoice.recognizer.primary/fallback 配置");
        }
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            InvoiceRecognizer recognizer = order.get(i);
            try {
                InvoiceRecognizer.RecognitionResult result = recognizer.recognize(filePath, contentType);
                boolean degraded = i > 0;
                if (degraded) {
                    log.info("发票识别降级到 {}（主实现失败：{}）", recognizer.name(), String.join("；", errors));
                }
                return new ChainResult(result, recognizer.name(), degraded);
            } catch (RecognitionException e) {
                errors.add(recognizer.name() + ": " + e.getMessage());
                log.warn("识别器 {} 失败：{}", recognizer.name(), e.getMessage());
            }
        }
        throw new RecognitionException("所有识别器都失败：" + String.join("；", errors));
    }

    /** 配置里的名字可能指向未启用的实现（比如 qwen 未开），跳过而不是炸掉 */
    private List<InvoiceRecognizer> resolveOrder() {
        List<InvoiceRecognizer> order = new ArrayList<>();
        addIfPresent(order, primary);
        if (fallback != null && !fallback.isBlank()) {
            addIfPresent(order, fallback);
        }
        return order;
    }

    private void addIfPresent(List<InvoiceRecognizer> order, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        recognizers.stream()
                .filter(r -> r.name().equals(name.trim()))
                .findFirst()
                .ifPresent(order::add);
    }

    public record ChainResult(InvoiceRecognizer.RecognitionResult result,
                              String recognizer,
                              boolean degraded) {
    }
}
