package com.occ.smartexpense.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 识别链降级策略单测（docs/11 §3.2）：主实现失败才降级，全失败才是失败。
 */
class InvoiceRecognizerChainTest {

    @Test
    void primary_fails_falls_back_and_marks_degraded() throws Exception {
        InvoiceRecognizerChain chain = new InvoiceRecognizerChain(
                List.of(failing("primary"), ok("fallback")), "primary", "fallback");

        InvoiceRecognizerChain.ChainResult result = chain.recognize("/tmp/a.png", "image/png");

        assertTrue(result.degraded());
        assertEquals("fallback", result.recognizer());
        assertEquals("C1", result.result().invoiceCode());
    }

    @Test
    void all_recognizers_fail_throws() {
        InvoiceRecognizerChain chain = new InvoiceRecognizerChain(
                List.of(failing("a"), failing("b")), "a", "b");

        RecognitionException ex = assertThrows(RecognitionException.class,
                () -> chain.recognize("/tmp/a.png", "image/png"));
        assertTrue(ex.getMessage().contains("所有识别器都失败"));
    }

    @Test
    void unknown_primary_is_skipped_so_fallback_still_works() throws Exception {
        InvoiceRecognizerChain chain = new InvoiceRecognizerChain(
                List.of(ok("mock")), "qwen-vl-not-enabled", "mock");

        InvoiceRecognizerChain.ChainResult result = chain.recognize("/tmp/a.png", "image/png");

        assertEquals("mock", result.recognizer());
        // 配置的第一个实现不存在时不算降级，只是跳过
        assertEquals(false, result.degraded());
    }

    @Test
    void no_usable_recognizer_throws_with_config_hint() {
        InvoiceRecognizerChain chain = new InvoiceRecognizerChain(
                List.of(ok("mock")), "nope", "");

        RecognitionException ex = assertThrows(RecognitionException.class,
                () -> chain.recognize("/tmp/a.png", "image/png"));
        assertTrue(ex.getMessage().contains("没有可用的识别器"));
    }

    private InvoiceRecognizer ok(String name) {
        return new InvoiceRecognizer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public RecognitionResult recognize(String filePath, String contentType) {
                return new RecognitionResult("C1", "N1", null, new BigDecimal("100.00"), null,
                        new BigDecimal("0.99"), List.of(), "{}");
            }
        };
    }

    private InvoiceRecognizer failing(String name) {
        return new InvoiceRecognizer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public RecognitionResult recognize(String filePath, String contentType) throws RecognitionException {
                throw new RecognitionException(name + " down");
            }
        };
    }

    /** 保持 Path 依赖可编译（未来链改成 Path 参数时不用改测试结构） */
    @SuppressWarnings("unused")
    private Path unusedPath() {
        return Path.of("/tmp");
    }
}
