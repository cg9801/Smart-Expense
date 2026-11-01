package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.entity.BudgetTxn;
import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.mapper.BudgetTxnMapper;
import com.occ.smartexpense.mapper.DeptBudgetMapper;
import com.occ.smartexpense.vo.BudgetVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算追加（mode=ADD）测试，对应 docs/03 §2.4 与 docs/06 §2.2。
 * seed：技术部(2) 2026-10 总额 50000；市场部(3) 20000；总池 100000。
 * 测试先放大总池到 200000，避免追加撞池上限。
 */
@SpringBootTest
@Transactional
class BudgetAddTest {

    private static final String PERIOD = "2026-10";
    private static final long DEPT_ID = 2L;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetTxnMapper budgetTxnMapper;

    @Autowired
    private DeptBudgetMapper deptBudgetMapper;

    @Test
    void add_mode_accumulates_total_and_writes_adjust_add_txn() {
        budgetService.setPool(PERIOD, new BigDecimal("200000.00"));
        BigDecimal before = currentTotal();

        BudgetVO after = budgetService.adjustBudget(DEPT_ID, PERIOD, new BigDecimal("10000.00"), "ADD");

        assertEquals(0, before.add(new BigDecimal("10000.00")).compareTo(after.getTotalAmount()));
        List<BudgetTxn> txns = txnsOf(DEPT_ID);
        // 至少有一条 ADJUST_ADD，金额 = 增量
        BudgetTxn add = txns.stream()
                .filter(t -> "ADJUST_ADD".equals(t.getTxnType()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, new BigDecimal("10000.00").compareTo(add.getAmount()));
        // 调整流水没有单据
        assertNull(add.getReportId());
    }

    @Test
    void default_set_mode_overwrites_total_and_writes_adjust_txn() {
        budgetService.setPool(PERIOD, new BigDecimal("200000.00"));
        BigDecimal before = currentTotal();

        BudgetVO after = budgetService.adjustBudget(DEPT_ID, PERIOD, before.add(new BigDecimal("5000.00")));

        assertEquals(0, before.add(new BigDecimal("5000.00")).compareTo(after.getTotalAmount()));
        assertTrue(txnsOf(DEPT_ID).stream().anyMatch(t -> "ADJUST".equals(t.getTxnType())));
    }

    @Test
    void add_beyond_pool_is_rejected() {
        budgetService.setPool(PERIOD, new BigDecimal("200000.00"));
        // 技术部 50000 + 追加 150000 = 200000；其他部门 20000 → 上限 180000，必然超
        BizException ex = assertThrows(BizException.class,
                () -> budgetService.adjustBudget(DEPT_ID, PERIOD, new BigDecimal("150000.00"), "ADD"));
        assertEquals(ErrorCode.STATE_ILLEGAL, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("超出总预算"));
    }

    @Test
    void unknown_mode_is_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> budgetService.adjustBudget(DEPT_ID, PERIOD, BigDecimal.ONE, "PLUS"));
        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
    }

    private BigDecimal currentTotal() {
        return budgetService.listBudgets(PERIOD).stream()
                .filter(b -> DEPT_ID == b.getDeptId())
                .map(BudgetVO::getTotalAmount)
                .findFirst()
                .orElseThrow();
    }

    private List<BudgetTxn> txnsOf(Long deptId) {
        DeptBudget budget = deptBudgetMapper.selectByDeptPeriod(deptId, (short) 2026, PERIOD);
        return budgetTxnMapper.selectList(new LambdaQueryWrapper<BudgetTxn>()
                .eq(BudgetTxn::getBudgetId, budget.getId())
                .orderByAsc(BudgetTxn::getId));
    }
}
