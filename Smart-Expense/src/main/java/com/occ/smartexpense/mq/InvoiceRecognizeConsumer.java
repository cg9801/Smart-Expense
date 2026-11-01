package com.occ.smartexpense.mq;

import com.occ.smartexpense.config.RabbitConfig;
import com.occ.smartexpense.service.InvoiceRecognitionService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 发票识别消费者（docs/11 §3）：
 * 识别失败是**业务结果**（落 FAILED/MANUAL），不 requeue，避免 MQ 放大模型调用；
 * 只有"连消息都处理不了"的异常才 nack 进死信，等人工。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceRecognizeConsumer {

    private final InvoiceRecognitionService recognitionService;

    @RabbitListener(queues = RabbitConfig.INVOICE_RECOGNIZE_QUEUE)
    public void onInvoiceUploaded(Message message, Channel channel) throws IOException {
        String msgId = message.getMessageProperties().getMessageId();
        long tag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            recognitionService.consume(msgId, body);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("发票识别消费失败 msgId={}", msgId, e);
            channel.basicNack(tag, false, false);
        }
    }
}
