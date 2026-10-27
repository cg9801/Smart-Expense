package com.occ.smartexpense.service.impl;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.entity.OutboxMessage;
import com.occ.smartexpense.mapper.OutboxMessageMapper;
import com.occ.smartexpense.service.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxMessageMapper messageMapper;
    @Override
    @Transactional
    public String append(String eventType, String aggregateType, Long aggregateId, String payloadJson) {
        if (payloadJson == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,"参数不能为空！");
        }
        String msgId = UUID.randomUUID().toString();
        OutboxMessage message = new OutboxMessage();
        message.setMsgId(msgId);
        message.setAggregateId(aggregateId);
        message.setAggregateType(aggregateType);
        message.setEventType(eventType);
        message.setPayload(payloadJson);
        message.setStatus("NEW");
        message.setRetryCount(0);
        message.setNextRetryAt(LocalDateTime.now());
        messageMapper.insert(message);
        return msgId;
    }
}
