package com.occ.smartexpense.statemachine;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.enums.ReportEvent;
import com.occ.smartexpense.enums.ReportStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 报销单状态机：表驱动，是唯一允许判定单据状态流转的地方。
 * 流转规则见 docs/02-状态机.md §3（T1–T6），非法组合一律抛 STATE_ILLEGAL。
 *
 * <p>为什么不写 if/else：状态越加越多时分支会爆炸，而且难以单测；
 * 用「状态 → 事件 → 允许的目标状态集合」一张表表达，加状态只改表。</p>
 */
@Component
public class ReportStateMachine {

    private static final Map<ReportStatus, Map<ReportEvent, Set<ReportStatus>>> TABLE = buildTable();

    private static Map<ReportStatus, Map<ReportEvent, Set<ReportStatus>>> buildTable() {
        Map<ReportStatus, Map<ReportEvent, Set<ReportStatus>>> table = new EnumMap<>(ReportStatus.class);

        // T1 提交
        table.put(ReportStatus.DRAFT, Map.of(
                ReportEvent.SUBMIT, Set.of(ReportStatus.PENDING)));

        // T2 撤回、T3/T4 通过、T5 驳回
        table.put(ReportStatus.PENDING, Map.of(
                ReportEvent.WITHDRAW, Set.of(ReportStatus.DRAFT),
                ReportEvent.APPROVE, EnumSet.of(ReportStatus.APPROVING, ReportStatus.APPROVED),
                ReportEvent.REJECT, Set.of(ReportStatus.REJECTED)));

        // 中间级通过仍是审批中，末级通过进入已通过
        table.put(ReportStatus.APPROVING, Map.of(
                ReportEvent.APPROVE, EnumSet.of(ReportStatus.APPROVING, ReportStatus.APPROVED),
                ReportEvent.REJECT, Set.of(ReportStatus.REJECTED)));

        // T6 付款回执成功
        table.put(ReportStatus.APPROVED, Map.of(
                ReportEvent.PAY_SUCCESS, Set.of(ReportStatus.PAID)));

        // 终态：无任何事件
        table.put(ReportStatus.REJECTED, Map.of());
        table.put(ReportStatus.PAID, Map.of());

        return Collections.unmodifiableMap(table);
    }

    /**
     * 目标唯一的事件：T1 提交、T2 撤回、T5 驳回、T6 付款成功。
     */
    public ReportStatus transit(ReportStatus from, ReportEvent event) {
        Set<ReportStatus> targets = targetsOf(from, event);
        if (targets.size() != 1) {
            throw new BizException(ErrorCode.STATE_ILLEGAL,
                    "事件 " + event + " 需要显式目标状态，候选 " + targets);
        }
        return targets.iterator().next();
    }

    /**
     * T3/T4 审批通过：末级进 APPROVED，否则进 APPROVING。
     */
    public ReportStatus approve(ReportStatus from, boolean lastStep) {
        ReportStatus target = lastStep ? ReportStatus.APPROVED : ReportStatus.APPROVING;
        Set<ReportStatus> targets = targetsOf(from, ReportEvent.APPROVE);
        if (!targets.contains(target)) {
            throw illegal(from, ReportEvent.APPROVE);
        }
        return target;
    }

    /** 供详情接口生成 availableActions，前端不自行推断状态机 */
    public boolean canTransit(ReportStatus from, ReportEvent event) {
        return TABLE.getOrDefault(from, Map.of()).containsKey(event);
    }

    /** 当前状态允许的事件集合（只读） */
    public Set<ReportEvent> eventsOf(ReportStatus from) {
        return Collections.unmodifiableSet(TABLE.getOrDefault(from, Map.of()).keySet());
    }

    private Set<ReportStatus> targetsOf(ReportStatus from, ReportEvent event) {
        Set<ReportStatus> targets = TABLE.getOrDefault(from, Map.of()).get(event);
        if (targets == null || targets.isEmpty()) {
            throw illegal(from, event);
        }
        return targets;
    }

    private BizException illegal(ReportStatus from, ReportEvent event) {
        return new BizException(ErrorCode.STATE_ILLEGAL,
                "状态 " + from + " 不允许事件 " + event);
    }
}
