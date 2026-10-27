package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.vo.PaymentVO;

public interface PaymentService {

    /** MQ 消费者用：按审批通过事件建付款单（幂等） */
    void createFromApproved(String msgId, String payloadJson);

    /** 付款单分页：status / payeeUserId / reportNo 均可选，见 docs/03 §2.4 */
    PageResult<PaymentVO> list(String status, Long payeeUserId, String reportNo, int page, int size);

    PaymentVO detail(Long paymentId);

    /** 模拟银行回执，触发 T6：付款单 CREATED→PAID，单据 APPROVED→PAID */
    PaymentVO confirm(Long paymentId, Long userId);
}
