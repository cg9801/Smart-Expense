package com.occ.smartexpense.invoice;

/**
 * 识别器不可用/输出无法解析。这是**业务结果**，不是消费异常：
 * 消费者捕获后把发票置 MANUAL/FAILED，绝不让 MQ 重投放大调用次数（docs/11 §3.5）。
 */
public class RecognitionException extends Exception {

    public RecognitionException(String message) {
        super(message);
    }

    public RecognitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
