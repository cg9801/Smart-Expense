package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.dto.ReportApprovedEvent;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.ExpenseStatusLog;
import com.occ.smartexpense.entity.PaymentOrder;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.enums.ReportEvent;
import com.occ.smartexpense.enums.ReportStatus;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.ExpenseStatusLogMapper;
import com.occ.smartexpense.mapper.MqConsumeLogMapper;
import com.occ.smartexpense.mapper.PaymentOrderMapper;
import com.occ.smartexpense.mapper.SysUserMapper;
import com.occ.smartexpense.service.PaymentService;
import com.occ.smartexpense.statemachine.ReportStateMachine;
import com.occ.smartexpense.vo.PaymentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 付款单服务：MQ 消费端建单（docs/07 §8） + 付款单查询与回执确认（T6，docs/02 §3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final DateTimeFormatter PAY_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String CONSUMER = "payment-create";
    private static final String PAYMENT_CREATED = "CREATED";
    private static final String PAYMENT_PAID = "PAID";
    private static final String ACTION_PAY = "PAY";

    private final MqConsumeLogMapper mqConsumeLogMapper;
    private final ObjectMapper objectMapper;
    private final PaymentOrderMapper paymentMapper;
    private final ExpenseReportMapper reportMapper;
    private final ExpenseStatusLogMapper statusLogMapper;
    private final SysUserMapper sysUserMapper;
    private final ReportStateMachine stateMachine;

    /**
     * 消费日志与付款单必须同一事务：任一失败整体回滚，消息重投时才能重新处理。
     * 事务提交后由 PaymentConsumer 负责 ack，顺序不能反（先 ack 再回滚会丢单）。
     */
    @Override
    @Transactional
    public void createFromApproved(String msgId, String payloadJson) {
        try {
            mqConsumeLogMapper.insert(msgId, CONSUMER);
        } catch (DuplicateKeyException e) {
            log.info("重复消息，跳过 msgId={}", msgId);
            return;
        }
        ReportApprovedEvent event = objectMapper.readValue(payloadJson, ReportApprovedEvent.class);
        if (event.reportId() == null || event.amountTotal() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批事件缺少必要字段");
        }
        BigDecimal amount = new BigDecimal(event.amountTotal());
        for (int i = 0; i < 3; i++) {
            try {
                paymentMapper.insert(buildOrder(event, amount, genPaymentNo()));
                return;                                  // 成功
            } catch (DuplicateKeyException e) {
                if (paymentMapper.selectByReportId(event.reportId()) != null) {
                    return;                              // uk_report：已建过单，幂等命中
                }
                // 否则是 uk_payment_no 撞了，循环换号重试
                log.warn("付款单号冲突，重试第 {} 次，reportId={}", i + 1, event.reportId());
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "付款单号连续冲突，reportId=" + event.reportId());
    }

    @Override
    public PageResult<PaymentVO> list(String status, Long payeeUserId, String reportNo, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 10;
        } else if (size > 100) {
            size = 100;
        }
        long total = paymentMapper.countPage(status, payeeUserId, reportNo);
        if (total == 0) {
            return PageResult.empty(page, size);
        }
        int offset = (page - 1) * size;
        List<PaymentVO> records = paymentMapper.selectPage(status, payeeUserId, reportNo, offset, size);
        return PageResult.of(records, total, page, size);
    }

    @Override
    public PaymentVO detail(Long paymentId) {
        PaymentVO vo = paymentMapper.selectVOById(paymentId);
        if (vo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "付款单不存在");
        }
        return vo;
    }

    /**
     * T6 付款回执：守卫见 docs/02 §3。
     * 付款单与单据都用条件更新，0 行即状态冲突，整体回滚。
     */
    @Override
    @Transactional
    public PaymentVO confirm(Long paymentId, Long userId) {
        PaymentOrder payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "付款单不存在");
        }
        if (!PAYMENT_CREATED.equals(payment.getStatus())) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "付款单当前状态不允许确认付款");
        }
        ExpenseReport report = reportMapper.selectById(payment.getReportId());
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报销单不存在");
        }
        // 守卫③：单据必须 APPROVED（T6 唯一判定在状态机里）
        ReportStatus to = stateMachine.transit(ReportStatus.valueOf(report.getStatus()), ReportEvent.PAY_SUCCESS);
        // 守卫④：金额必须一致，不一致直接拒绝，不自动改数（留给对账）
        if (payment.getAmount().compareTo(report.getAmountTotal()) != 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL,
                    "付款单金额与报销单不一致：payment=" + payment.getAmount()
                            + ", report=" + report.getAmountTotal());
        }

        LocalDateTime now = LocalDateTime.now();
        int paymentRows = paymentMapper.update(null, new LambdaUpdateWrapper<PaymentOrder>()
                .eq(PaymentOrder::getId, paymentId)
                .eq(PaymentOrder::getStatus, PAYMENT_CREATED)
                .set(PaymentOrder::getStatus, PAYMENT_PAID)
                .set(PaymentOrder::getPaidAt, now)
                .setSql("version = version + 1"));
        if (paymentRows == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "付款单状态已变化，请刷新后重试");
        }

        int reportRows = reportMapper.update(null, new LambdaUpdateWrapper<ExpenseReport>()
                .eq(ExpenseReport::getId, report.getId())
                .eq(ExpenseReport::getStatus, ReportStatus.APPROVED.name())
                .set(ExpenseReport::getStatus, to.name())
                .set(ExpenseReport::getPaidAt, now));
        if (reportRows == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "报销单状态已变化，请刷新后重试");
        }

        SysUser operator = sysUserMapper.selectById(userId);
        writeStatusLog(report.getId(), ACTION_PAY, ReportStatus.APPROVED.name(), to.name(),
                userId, operator == null ? null : operator.getRealName(),
                payDetail(payment));
        return detail(paymentId);
    }

    private PaymentOrder buildOrder(ReportApprovedEvent event, BigDecimal amount, String paymentNo) {
        PaymentOrder order = new PaymentOrder();
        order.setPaymentNo(paymentNo);
        order.setReportId(event.reportId());
        order.setPayeeUserId(event.applicantId());     // 收款人 = 申请人，来自 payload 快照
        order.setPayeeName(event.applicantName());
        order.setAmount(amount);
        order.setStatus(PAYMENT_CREATED);
        order.setRemark("报销单 " + event.reportNo());
        return order;
    }

    private String genPaymentNo() {
        return "PAY"
                + LocalDateTime.now().format(PAY_NO_DATE)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /** 审计：一次确认一条，detail 记付款单信息（docs/02 §8） */
    private void writeStatusLog(Long reportId, String action, String fromStatus, String toStatus,
                                Long operatorId, String operatorName, String detail) {
        ExpenseStatusLog log = new ExpenseStatusLog();
        log.setTraceId("PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        log.setReportId(reportId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setDetail(detail);
        statusLogMapper.insert(log);
    }

    private String payDetail(PaymentOrder payment) {
        return "{\"paymentId\":" + payment.getId()
                + ",\"paymentNo\":\"" + payment.getPaymentNo()
                + "\",\"amount\":\"" + payment.getAmount().toPlainString() + "\"}";
    }
}
