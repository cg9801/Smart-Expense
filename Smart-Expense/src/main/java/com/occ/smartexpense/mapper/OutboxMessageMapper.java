package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.OutboxMessage;

/**
 * Outbox 消息数据访问。投递相关的条件更新（租约/标记 SENT/退避）在 OutboxRelay 里用 LambdaUpdateWrapper 完成，
 * 这样 "WHERE status='NEW' AND next_retry_at <= NOW()" 与更新是同一条 SQL，避免读改写竞争。
 */
public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {
}
