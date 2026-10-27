package com.occ.smartexpense.service;

public interface OutboxService {

    String append(String eventType, String aggregateType, Long aggregateId, String payloadJson);


}
