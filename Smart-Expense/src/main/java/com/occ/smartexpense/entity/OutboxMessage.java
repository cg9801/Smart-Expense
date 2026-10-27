package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Outbox 本地消息表 outbox_message，字段见 sql/schema.sql §5 与 docs/07-Outbox与消息一致性.md §3.1。
 * 与业务在同一本地事务写入：事务提交即"消息必达"，投递由 OutboxRelay 轮询完成。
 */
@Data
@TableName("outbox_message")
public class OutboxMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** UUID，投递时作为 MQ messageId */
    private String msgId;

    /** 聚合类型，如 REPORT */
    private String aggregateType;

    private Long aggregateId;

    /** 事件类型，如 REPORT_APPROVED */
    private String eventType;

    /** 事件体 JSON（快照，消费者不再回查业务表） */
    private String payload;

    /** NEW / SENT / FAILED / DEAD */
    private String status;

    private Integer retryCount;

    /** 下次可投递时间：调度器扫描条件 + 多实例租约 */
    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime sentAt;
}
