package com.occ.smartexpense.mq;

import com.occ.smartexpense.config.RabbitConfig;
import com.occ.smartexpense.service.PaymentService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConsumer {
    private final PaymentService paymentService;

    // 消费者
    @RabbitListener(queues = RabbitConfig.REPORT_APPROVED_QUEUE)
    public void onReportApproved(Message message, Channel channel) throws IOException{
        String msgId = message.getMessageProperties().getMessageId();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        long tag = message.getMessageProperties().getDeliveryTag();
        try{
            paymentService.createFromApproved(msgId,body);
            channel.basicAck(tag,false);
        }catch (Exception e){
            log.info("建付款单失败，msgId={}",msgId,e);
            channel.basicNack(tag,false,false);
        }
    }
}
