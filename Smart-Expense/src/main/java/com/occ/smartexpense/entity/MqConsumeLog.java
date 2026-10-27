package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费幂等记录 mq_consume_log，见 docs/07 §3.2。
 * 主键是 (msg_id, consumer)，没有自增 id，因此不使用 BaseMapper，写入手写 @Insert。
 */
@Data
@TableName("mq_consume_log")
public class MqConsumeLog {

    /** MQ messageId（= outbox_message.msg_id） */
    private String msgId;

    /** 消费者标识，如 payment-create */
    private String consumer;

    private LocalDateTime consumedAt;
}
