package com.occ.smartexpense.job;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.config.RabbitConfig;
import com.occ.smartexpense.entity.OutboxMessage;
import com.occ.smartexpense.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.util.StringUtils.truncate;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxMessageMapper messageMapper;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 2000)
    public void relay(){
        LocalDateTime now = LocalDateTime.now();
        List<OutboxMessage> list = messageMapper.selectList(new LambdaQueryWrapper<OutboxMessage>()
                .in(OutboxMessage::getStatus, "NEW", "FAILED")
                .le(OutboxMessage::getNextRetryAt, now)
                .orderByAsc(OutboxMessage::getId)
                .last("LIMIT 50")
        );
        for (OutboxMessage m : list) {
            try {
                if(!lease(m)){
                    continue;
                }
                publish(m);
                markSent(m);
            }catch (Exception e){
                log.warn("投递失败，msgId={},retry={}",m.getMsgId(),m.getRetryCount()+1,e);
                try {
                    markFailed(m, e);
                } catch (Exception inner) {
                    log.error("标记失败也挂了 id={}", m.getId(), inner);
                }
            }
        }
    }
    private boolean lease(OutboxMessage message){
        LocalDateTime now = LocalDateTime.now();
        // 显式用 update(null, wrapper)：单参 update(wrapper) 是接口默认方法，Mockito 打桩时容易被绕过
        int locked = messageMapper.update(null, new LambdaUpdateWrapper<OutboxMessage>()
                .eq(OutboxMessage::getId, message.getId())
                .in(OutboxMessage::getStatus, "NEW", "FAILED")
                .le(OutboxMessage::getNextRetryAt, now)
                .set(OutboxMessage::getNextRetryAt, now.plusSeconds(30))
        );
        return locked != 0;
    }
    private void publish(OutboxMessage message){
        rabbitTemplate.convertAndSend(
                RabbitConfig.REPORT_EXCHANGE,
                routingKeyOf(message.getEventType()),
                message.getPayload(),
                m ->{
                    m.getMessageProperties().setMessageId(message.getMsgId());
                    m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    m.getMessageProperties().setContentType("application/json");
                    return m;
                }
        );
    }

    /** 按事件类型选路由键：提交 → AI 审核队列，通过 → 付款队列（docs/11 §5.2） */
    private String routingKeyOf(String eventType) {
        return switch (eventType) {
            case "REPORT_APPROVED" -> RabbitConfig.REPORT_APPROVED_ROUTING_KEY;
            case "REPORT_SUBMITTED" -> RabbitConfig.REPORT_SUBMITTED_ROUTING_KEY;
            default -> throw new IllegalArgumentException("未知事件类型: " + eventType);
        };
    }
    private void markSent(OutboxMessage message){
        messageMapper.update(null, new LambdaUpdateWrapper<OutboxMessage>()
                .eq(OutboxMessage::getId, message.getId())
                .in(OutboxMessage::getStatus, "NEW", "FAILED")   // 防止把 DEAD 的又改回来
                .set(OutboxMessage::getStatus, "SENT")
                .set(OutboxMessage::getSentAt, LocalDateTime.now()));
    }
    private void markFailed(OutboxMessage message,Exception e){
        int retry = message.getRetryCount() + 1;
        String err = String.valueOf(e);
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<OutboxMessage> uw = new LambdaUpdateWrapper<OutboxMessage>()
                .eq(OutboxMessage::getId,message.getId())
                .in(OutboxMessage::getStatus, "NEW", "FAILED")
                .set(OutboxMessage::getRetryCount,retry)
                .set(OutboxMessage::getLastError,truncate(err,512))
                .set(OutboxMessage::getNextRetryAt,now.plusSeconds(backoffSeconds(retry)))
                .set(OutboxMessage::getStatus, statusAfterRetry(retry));
        messageMapper.update(null, uw);
    }

    /** 重试到第 5 次进入 DEAD，不再自动投递，等对账兜底 */
    static String statusAfterRetry(int retry) {
        return retry >= 5 ? "DEAD" : "FAILED";
    }

    /** 指数退避：5,10,20,40,80… 封顶 300 秒 */
    static long backoffSeconds(int retry) {
        return Math.min(5L * (1L << (retry - 1)), 300L);
    }


}
