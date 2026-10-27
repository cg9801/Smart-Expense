package com.occ.smartexpense.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.occ.smartexpense.config.RabbitConfig;
import com.occ.smartexpense.entity.OutboxMessage;
import com.occ.smartexpense.mapper.OutboxMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay 纯单元测试：不启 Spring、不连库、不发真消息（docs/07 §12 第 3 步）。
 *
 * <p>注意两点：
 * <ul>
 *   <li>LambdaUpdateWrapper 依赖 MyBatis-Plus 的 TableInfo 缓存，纯单测里必须手动初始化，
 *       否则会抛 {@code can not find lambda cache for this entity}；</li>
 *   <li>BaseMapper.update 有单参默认方法和双参抽象方法，生产代码统一用双参，测试也只 stub 双参，
 *       避免单参默认方法在 Mockito 下被绕过导致"假通过"。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxMessageMapper messageMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OutboxRelay relay;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OutboxMessage.class);
    }

    private OutboxMessage newMessage() {
        OutboxMessage m = new OutboxMessage();
        m.setId(1L);
        m.setMsgId("uuid-1");
        m.setPayload("{\"reportId\":1}");
        m.setStatus("NEW");
        m.setRetryCount(0);
        return m;
    }

    @Test
    @DisplayName("租约抢到且投递成功 → 发到 exchange，并标记 SENT")
    void relay_publishes_and_marks_sent() {
        when(messageMapper.selectList(any())).thenReturn(List.of(newMessage()));
        when(messageMapper.update(isNull(), any(Wrapper.class))).thenReturn(1); // 租约 + markSent

        relay.relay();

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.REPORT_EXCHANGE),
                eq(RabbitConfig.REPORT_APPROVED_ROUTING_KEY),
                eq("{\"reportId\":1}"),
                any(MessagePostProcessor.class));
        verify(messageMapper, times(2)).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("租约没抢到 → 跳过，不投递")
    void relay_skips_when_lease_lost() {
        when(messageMapper.selectList(any())).thenReturn(List.of(newMessage()));
        when(messageMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        relay.relay();

        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(), any(MessagePostProcessor.class));
        verify(messageMapper, times(1)).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("投递抛异常 → 不中断整轮，走失败标记（租约 + 标记共 2 次 update）")
    void relay_marks_failed_without_throwing() {
        when(messageMapper.selectList(any())).thenReturn(List.of(newMessage()));
        when(messageMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        doThrow(new AmqpException("mq down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));

        relay.relay();

        verify(messageMapper, times(2)).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("退避公式：5s 起翻倍，封顶 300s")
    void backoff_is_exponential_and_capped() {
        assertEquals(5L, OutboxRelay.backoffSeconds(1));
        assertEquals(10L, OutboxRelay.backoffSeconds(2));
        assertEquals(80L, OutboxRelay.backoffSeconds(5));
        assertEquals(300L, OutboxRelay.backoffSeconds(10));
    }

    @Test
    @DisplayName("第 5 次失败进 DEAD，之前是 FAILED")
    void status_after_retry() {
        assertEquals("FAILED", OutboxRelay.statusAfterRetry(1));
        assertEquals("FAILED", OutboxRelay.statusAfterRetry(4));
        assertEquals("DEAD", OutboxRelay.statusAfterRetry(5));
    }
}
