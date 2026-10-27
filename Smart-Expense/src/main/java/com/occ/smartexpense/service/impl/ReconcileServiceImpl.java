package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.entity.BudgetTxn;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.OutboxMessage;
import com.occ.smartexpense.entity.PaymentOrder;
import com.occ.smartexpense.entity.ReconcileDiff;
import com.occ.smartexpense.entity.ReconcileRecord;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.mapper.BudgetTxnMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.OutboxMessageMapper;
import com.occ.smartexpense.mapper.PaymentOrderMapper;
import com.occ.smartexpense.mapper.ReconcileDiffMapper;
import com.occ.smartexpense.mapper.ReconcileRecordMapper;
import com.occ.smartexpense.mapper.SysUserMapper;
import com.occ.smartexpense.service.BudgetService;
import com.occ.smartexpense.service.OutboxService;
import com.occ.smartexpense.service.ReconcileService;
import com.occ.smartexpense.vo.ReconcileDiffVO;
import com.occ.smartexpense.vo.ReconcileRecordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账实现骨架（docs/08 §8/§9）。
 * run() 已补完四类检查与自动修复；列表与人工修复仍是 TODO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileServiceImpl implements ReconcileService {

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReconcileRecordMapper recordMapper;
    private final ReconcileDiffMapper diffMapper;
    private final ExpenseReportMapper reportMapper;
    private final BudgetTxnMapper budgetTxnMapper;
    private final PaymentOrderMapper paymentOrderMapper;
    private final OutboxMessageMapper outboxMessageMapper;
    private final SysUserMapper sysUserMapper;
    private final BudgetService budgetService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Override
    public ReconcileRecordVO run(LocalDate bizDate) {
        //1) 幂等查批次（uk_biz_date）
        ReconcileRecord existing = recordMapper.selectByBizDate(bizDate);
        if (existing != null) {
            log.info("对账批次已存在，bizDate={}，status={}",bizDate,existing.getStatus());
            return toVO(existing);
        }

        //2) 建 RUNNING 批次
        ReconcileRecord record = new ReconcileRecord();
        record.setBizDate(bizDate);
        record.setScannedCount(0);
        record.setDiffCount(0);
        record.setStatus("RUNNING");
        record.setStartedAt(LocalDateTime.now());
        recordMapper.insert(record);

        try {
            //3) 扫 DATE(finished_at)=bizDate 且 status IN (APPROVED, PAID)
            //   finished_at 为空的单也要捞：否则 STATUS_STUCK（状态有了、时间戳没有）永远扫不到
            List<ExpenseReport> reports = reportMapper.selectList(new LambdaQueryWrapper<ExpenseReport>()
                    .in(ExpenseReport::getStatus, "APPROVED", "PAID")
                    .and(w -> w.apply("DATE(finished_at) = {0}", bizDate)
                            .or().isNull(ExpenseReport::getFinishedAt)));
            int diffCount = 0;
            for (ExpenseReport report : reports) {
                List<String> mismatches = new ArrayList<>();
                PaymentOrder order = paymentOrderMapper.selectByReportId(report.getId());
                BudgetTxn deduct = budgetTxnMapper.selectOne(new LambdaQueryWrapper<BudgetTxn>()
                        .eq(BudgetTxn::getReportId, report.getId())
                        .eq(BudgetTxn::getTxnType, "DEDUCT")
                        .last("LIMIT 1"));

                // ① 缺付款单：对账只重放消息，付款单由消费者建（docs/08 §3.2）
                if (order == null) {
                    diffCount += saveAndRepair(bizDate, "PAYMENT_MISSING", report,
                            "{\"reportNo\":\"" + report.getReportNo()
                                    + "\",\"amountTotal\":\"" + report.getAmountTotal().toPlainString() + "\"}");
                }

                // ② 金额不一致：两个来源合并成一条，避免撞 uk_date_type_report
                if (order != null && order.getAmount().compareTo(report.getAmountTotal()) != 0) {
                    mismatches.add("payment=" + order.getAmount().toPlainString());
                }
                if (deduct != null && deduct.getAmount().compareTo(report.getAmountTotal()) != 0) {
                    mismatches.add("budgetTxn=" + deduct.getAmount().toPlainString());
                }
                if (!mismatches.isEmpty()) {
                    diffCount += saveAndRepair(bizDate, "AMOUNT_MISMATCH", report,
                            "{\"reportAmount\":\"" + report.getAmountTotal().toPlainString()
                                    + "\",\"mismatches\":[\"" + String.join("\",\"", mismatches) + "\"]}");
                }

                // ③ 缺实扣流水：budget_id 为空的是预算功能上线前的历史单，跳过
                if (report.getBudgetId() != null && deduct == null) {
                    diffCount += saveAndRepair(bizDate, "BUDGET_NOT_DEDUCTED", report,
                            "{\"reportNo\":\"" + report.getReportNo()
                                    + "\",\"amountTotal\":\"" + report.getAmountTotal().toPlainString() + "\"}");
                }

                // ④ 状态卡死：APPROVED 没 finished_at / PAID 没 paid_at
                String stuck = stuckReason(report);
                if (stuck != null) {
                    diffCount += saveAndRepair(bizDate, "STATUS_STUCK", report,
                            "{\"reportNo\":\"" + report.getReportNo()
                                    + "\",\"status\":\"" + report.getStatus()
                                    + "\",\"reason\":\"" + stuck + "\"}");
                }
            }
            record.setScannedCount(reports.size());
            record.setDiffCount(diffCount);
            record.setStatus("DONE");
        } catch (Exception e) {
            // 扫描/检查阶段异常：批次置 FAILED 再抛，失败原因留在 detail 里
            log.error("对账批次失败 bizDate={}", bizDate, e);
            record.setStatus("FAILED");
            record.setDetail("{\"error\":\"" + escapeJson(String.valueOf(e)) + "\"}");
            record.setFinishedAt(LocalDateTime.now());
            recordMapper.updateById(record);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "对账失败：" + e.getMessage());
        }

        record.setFinishedAt(LocalDateTime.now());
        recordMapper.updateById(record);
        return toVO(record);
    }

    /**
     * 写差异 + 尝试自动修复。
     * 已存在同一 (bizDate, diffType, reportId) 时只刷新 detail，不重复计数。
     *
     * @return 新增返回 1，已存在返回 0
     */
    private int saveAndRepair(LocalDate bizDate, String diffType, ExpenseReport report, String detail) {
        ReconcileDiff exist = diffMapper.selectOne(new LambdaQueryWrapper<ReconcileDiff>()
                .eq(ReconcileDiff::getBizDate, bizDate)
                .eq(ReconcileDiff::getDiffType, diffType)
                .eq(ReconcileDiff::getReportId, report.getId())
                .last("LIMIT 1"));
        if (exist != null) {
            exist.setDetail(detail);
            diffMapper.updateById(exist);
            return 0;
        }

        ReconcileDiff diff = new ReconcileDiff();
        diff.setBizDate(bizDate);
        diff.setDiffType(diffType);
        diff.setReportId(report.getId());
        diff.setStatus("OPEN");
        diff.setDetail(detail);
        try {
            diffMapper.insert(diff);
        } catch (DuplicateKeyException e) {
            // 并发对账时另一线程刚插进去，本次不重复记录
            log.info("差异已存在，跳过 bizDate={}, type={}, reportId={}", bizDate, diffType, report.getId());
            return 0;
        }

        repair(diff, report);
        return 1;
    }

    /** 只自动修前两类；金额/状态类保持 OPEN 转人工 */
    private void repair(ReconcileDiff diff, ExpenseReport report) {
        try {
            switch (diff.getDiffType()) {
                // 补扣走 BudgetService：frozen 不足时它抛 40901，正好挡住乱扣
                case "BUDGET_NOT_DEDUCTED" -> budgetService.deduct(report);
                // 补付款单走消息重放，不直接插付款单
                case "PAYMENT_MISSING" -> replayReportApproved(report);
                default -> {
                    return;
                }
            }
            diff.setStatus("REPAIRED");
            diff.setRepairedAt(LocalDateTime.now());
            diff.setHandlerId(null);   // NULL 表示自动补偿
            diffMapper.updateById(diff);
        } catch (Exception e) {
            // 修复失败：保持 OPEN，原因追加进 detail，下一轮对账还会报
            log.warn("差异修复失败 diffId={}, type={}", diff.getId(), diff.getDiffType(), e);
            diff.setDetail(appendRepairError(diff.getDetail(), e));
            diffMapper.updateById(diff);
        }
    }

    /**
     * 重放 REPORT_APPROVED（docs/08 §3.2）：
     * 没有记录或已 SENT → 补写一条新的；NEW/FAILED/DEAD → 重置为 NEW 立即到期。
     */
    private void replayReportApproved(ExpenseReport report) {
        OutboxMessage latest = outboxMessageMapper.selectOne(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getAggregateType, "REPORT")
                .eq(OutboxMessage::getAggregateId, report.getId())
                .eq(OutboxMessage::getEventType, "REPORT_APPROVED")
                .orderByDesc(OutboxMessage::getId)
                .last("LIMIT 1"));

        if (latest == null || "SENT".equals(latest.getStatus())) {
            outboxService.append("REPORT_APPROVED", "REPORT", report.getId(), buildApprovedPayload(report));
            return;
        }

        outboxMessageMapper.update(null, new LambdaUpdateWrapper<OutboxMessage>()
                .eq(OutboxMessage::getId, latest.getId())
                .set(OutboxMessage::getStatus, "NEW")
                .set(OutboxMessage::getRetryCount, 0)
                .set(OutboxMessage::getNextRetryAt, LocalDateTime.now()));
    }

    /** 事件体是快照，字段与审批通过时写入的一致（docs/07 §5） */
    private String buildApprovedPayload(ExpenseReport report) {
        SysUser applicant = sysUserMapper.selectById(report.getApplicantId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", report.getId());
        payload.put("reportNo", report.getReportNo());
        payload.put("applicantId", report.getApplicantId());
        payload.put("applicantName", applicant == null ? null : applicant.getRealName());
        payload.put("amountTotal", report.getAmountTotal().toPlainString());
        payload.put("currency", report.getCurrency());
        payload.put("approvedAt", report.getFinishedAt() == null
                ? LocalDateTime.now().format(EVENT_TIME)
                : report.getFinishedAt().format(EVENT_TIME));
        payload.put("source", "reconcile");   // 标记补偿重放，排查时能区分
        return objectMapper.writeValueAsString(payload);
    }

    private String stuckReason(ExpenseReport report) {
        if ("APPROVED".equals(report.getStatus()) && report.getFinishedAt() == null) {
            return "APPROVED without finished_at";
        }
        if ("PAID".equals(report.getStatus()) && report.getPaidAt() == null) {
            return "PAID without paid_at";
        }
        return null;
    }

    /** 在已有 JSON 对象末尾追加 repairError 字段 */
    private String appendRepairError(String detail, Exception e) {
        String payload = detail == null || detail.isBlank() ? "{}" : detail;
        String error = escapeJson(String.valueOf(e));
        int end = payload.lastIndexOf('}');
        if (end < 0) {
            return "{\"repairError\":\"" + error + "\"}";
        }
        return payload.substring(0, end) + ",\"repairError\":\"" + error + "\"}";
    }

    private String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ReconcileRecordVO toVO(ReconcileRecord record) {
        ReconcileRecordVO vo = new ReconcileRecordVO();
        BeanUtils.copyProperties(record,vo);
        return vo;
    }

    @Override
    public PageResult<ReconcileRecordVO> listRecords(int page, int size) {
        // TODO：分页钳制 + selectPage（BaseMapper 自带分页或手写 LIMIT）+ Entity → VO
        page = Math.max(page,1);
        size = size < 1 ? 10 :Math.min(size,100);
        Long total = recordMapper.selectCount(null);
        if (total == 0) {
            return PageResult.empty(page,size);
        }
        int offset = (page-1) * size;
        List<ReconcileRecord> rows = recordMapper.selectList(new LambdaQueryWrapper<ReconcileRecord>()
                .orderByDesc(ReconcileRecord::getId)
                .last(" LIMIT " + size + " OFFSET " + offset));
        return PageResult.of(rows.stream().map(this::toVO).toList(),total,page,size);
    }

    @Override
    public PageResult<ReconcileDiffVO> listDiffs(String status, String diffType, LocalDate bizDate,
                                                 int page, int size) {
        page = Math.max(page, 1);
        size = size < 1 ? 10 : Math.min(size, 100);
        long total = diffMapper.countPage(status, diffType, bizDate);
        if (total == 0) {
            return PageResult.empty(page, size);
        }
        int offset = (page - 1) * size;
        List<ReconcileDiff> rows = diffMapper.selectPage(status, diffType, bizDate, offset, size);
        return PageResult.of(rows.stream().map(this::toDiffVO).toList(), total, page, size);
    }
    private ReconcileDiffVO toDiffVO(ReconcileDiff diff) {
        ReconcileDiffVO vo = new ReconcileDiffVO();
        BeanUtils.copyProperties(diff, vo);
        return vo;
    }
    @Override
    @Transactional
    public void repair(Long diffId, Long userId) {
        ReconcileDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new BizException(ErrorCode.NOT_FOUND,"对账差异不存在");
        }
        if (!"OPEN".equals(diff.getStatus())){
            throw new BizException(ErrorCode.STATE_ILLEGAL,"当前状态不允许修复");
        }
        ExpenseReport report = reportMapper.selectById(diff.getReportId());
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "关联报销单不存在");
        }
        switch (diff.getDiffType()) {
            case "BUDGET_NOT_DEDUCTED" -> budgetService.deduct(report);
            case "PAYMENT_MISSING" -> replayReportApproved(report);
            default -> { }
        }
        diff.setStatus("REPAIRED");
        diff.setRepairedAt(LocalDateTime.now());
        diff.setHandlerId(userId);      // 人工修复记操作人；自动修复是 null
        diffMapper.updateById(diff);
    }
}
