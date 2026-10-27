package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.vo.ReconcileDiffVO;
import com.occ.smartexpense.vo.ReconcileRecordVO;

import java.time.LocalDate;

/**
 * 对账服务，完整流程见 docs/08-对账与自动补偿.md。
 */
public interface ReconcileService {

    /**
     * 跑一次对账（docs/08 §4）：扫描 → 写批次 → 四类检查 → 自动修复 → 收尾。
     * 同一 bizDate 已有 DONE 批次时直接返回该批次（幂等）。
     */
    ReconcileRecordVO run(LocalDate bizDate);

    PageResult<ReconcileRecordVO> listRecords(int page, int size);

    PageResult<ReconcileDiffVO> listDiffs(String status, String diffType, LocalDate bizDate, int page, int size);

    /** 人工修复：只允许 OPEN → REPAIRED，复用与自动修复相同的服务方法（docs/08 §7） */
    void repair(Long diffId, Long userId);
}
