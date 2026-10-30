package com.occ.smartexpense.mq;

import com.occ.smartexpense.config.RabbitConfig;
import com.occ.smartexpense.service.AiReviewService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AI 审核消费者：提交事件 → 跑 Agent 建议（docs/11 §5.2）。
 * 消费日志与落库在 AiReviewService.consume 的同一事务里；失败只记日志，不重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiReviewConsumer {

    private final AiReviewService aiReviewService;

    @RabbitListener(queues = RabbitConfig.AI_REVIEW_QUEUE)
    public void onReportSubmitted(Message message, Channel channel) throws IOException {
        String msgId = message.getMessageProperties().getMessageId();
        long tag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            aiReviewService.consume(msgId, body);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            // 走到这里说明连 consume 的事务都没兜住（极少），进死信等人工
            log.error("AI 审核消费失败 msgId={}", msgId, e);
            channel.basicNack(tag, false, false);
        }
    }
}
