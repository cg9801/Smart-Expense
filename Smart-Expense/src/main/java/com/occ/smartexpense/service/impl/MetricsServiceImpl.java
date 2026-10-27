package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.OutboxMessage;
import com.occ.smartexpense.entity.PaymentOrder;
import com.occ.smartexpense.entity.ReconcileDiff;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.OutboxMessageMapper;
import com.occ.smartexpense.mapper.PaymentOrderMapper;
import com.occ.smartexpense.mapper.ReconcileDiffMapper;
import com.occ.smartexpense.service.MetricsService;
import com.occ.smartexpense.vo.MetricsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 指标聚合：只读现有表，不落库、不加缓存（docs/10 §4/§9）。
 * 每次请求约 7 条 SQL，数据量上来后按文档加 Redis 缓存或预计算。
 */
@Service
@RequiredArgsConstructor
public class MetricsServiceImpl implements MetricsService {

    private static final String REPORT_APPROVED = "APPROVED";
    private static final String REPORT_PAID = "PAID";
    private static final String REPORT_REJECTED = "REJECTED";
    private static final String PAYMENT_CREATED = "CREATED";
    private static final String DIFF_OPEN = "OPEN";
    private static final String OUTBOX_DEAD = "DEAD";

    private final ExpenseReportMapper reportMapper;
    private final PaymentOrderMapper paymentOrderMapper;
    private final ReconcileDiffMapper reconcileDiffMapper;
    private final OutboxMessageMapper outboxMessageMapper;

    @Override
    public MetricsVO overview() {
        MetricsVO vo = new MetricsVO();

        // ① 提交数：submit_token 非空的单（deleted 由逻辑删除插件自动加）
        vo.setSubmitCount(reportMapper.selectCount(new LambdaQueryWrapper<ExpenseReport>()
                .isNotNull(ExpenseReport::getSubmitToken)));

        // ② 通过数：PAID 也算通过过（docs/10 §3）
        vo.setApprovedCount(reportMapper.selectCount(new LambdaQueryWrapper<ExpenseReport>()
                .in(ExpenseReport::getStatus, REPORT_APPROVED, REPORT_PAID)));

        // ③ 驳回数
        vo.setRejectedCount(reportMapper.selectCount(new LambdaQueryWrapper<ExpenseReport>()
                .eq(ExpenseReport::getStatus, REPORT_REJECTED)));

        // ④ 通过率：分母只算已出结果的单，在途单不稀释
        vo.setApproveRate(rate(vo.getApprovedCount(), vo.getRejectedCount()));

        // ⑤ 平均审批耗时：SQL 算分钟，这里转小时；空表时 AVG 返回 null
        Double avgMinutes = reportMapper.avgApprovalMinutes();
        vo.setAvgApprovalHours(avgMinutes == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(avgMinutes)
                        .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));

        // ⑥ 待付款
        vo.setPaymentPending(paymentOrderMapper.selectCount(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getStatus, PAYMENT_CREATED)));

        // ⑦ 待处理对账差异
        vo.setReconcileOpenDiffs(reconcileDiffMapper.selectCount(new LambdaQueryWrapper<ReconcileDiff>()
                .eq(ReconcileDiff::getStatus, DIFF_OPEN)));

        // ⑧ 投递放弃的消息
        vo.setOutboxDead(outboxMessageMapper.selectCount(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getStatus, OUTBOX_DEAD)));

        return vo;
    }

    /**
     * 通过率：分母为 0 时返回 0，避免除零；必须显式给 scale，否则除不尽会抛 ArithmeticException。
     */
    private BigDecimal rate(long approved, long rejected) {
        long total = approved + rejected;
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(approved)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
