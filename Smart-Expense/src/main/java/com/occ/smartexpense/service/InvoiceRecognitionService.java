package com.occ.smartexpense.service;

/**
 * 发票识别链路（docs/11 §3）：上传后异步识别，只动 invoice 表。
 *
 * <p>两条路径严格分开（docs/11 §3.5）：
 * <ul>
 *   <li>{@code enqueue} 只写 Outbox —— 负责"消息一定投出去"；</li>
 *   <li>{@code consume} 做业务识别 —— 失败是业务结果，不 requeue，不放大调用。</li>
 * </ul>
 */
public interface InvoiceRecognitionService {

    /** 上传发票后入队：与上传同事务写 outbox（docs/07） */
    void enqueue(Long invoiceId);

    /**
     * MQ 消费入口：消费日志幂等 → 抢占 PENDING→PROCESSING → 识别 → 回写。
     * 识别耗时在事务外，回写用条件更新，重复消息不会覆盖人工复核结果。
     */
    void consume(String msgId, String payloadJson);
}
