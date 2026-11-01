package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.entity.BudgetTxn;
import com.occ.smartexpense.entity.BudgetPool;
import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.SysDept;
import com.occ.smartexpense.mapper.BudgetTxnMapper;
import com.occ.smartexpense.mapper.BudgetPoolMapper;
import com.occ.smartexpense.mapper.DeptBudgetMapper;
import com.occ.smartexpense.mapper.SysDeptMapper;
import com.occ.smartexpense.service.BudgetService;
import com.occ.smartexpense.vo.BudgetPoolVO;
import com.occ.smartexpense.vo.BudgetVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 预算动作实现，步骤与 docs/06-预算冻结与扣减.md §4 一一对应。
 * 骨架阶段：先让编译通过，逻辑按文档 §8 的落地顺序逐个补。
 */
@Service
@RequiredArgsConstructor
public class BudgetServiceImpl implements BudgetService {

    private final DeptBudgetMapper deptBudgetMapper;
    private final BudgetTxnMapper budgetTxnMapper;
    private final SysDeptMapper sysDeptMapper;
    private final BudgetPoolMapper budgetPoolMapper;

    @Override
    @Transactional
    public DeptBudget freeze(ExpenseReport report) {
        //§4.1：定位预算行 → 条件更新 frozen_amount → 插 FREEZE 流水 → 返回预算行
        if(report == null || report.getAmountTotal() == null || report.getAmountTotal().compareTo(BigDecimal.ZERO) <= 0){
            throw new BizException(ErrorCode.PARAM_ERROR, "金额必须大于 0");
        }
        //1.定位账期
        LocalDateTime begin = report.getSubmittedAt();
        if (begin == null){
            begin = LocalDateTime.now();
        }
//        财年 = 时间.年份 转 Short
        short fiscalYear =(short)begin.getYear();
        String period = begin.format(DateTimeFormatter.ofPattern("yyyy-MM"));
//        账期 = 时间 按 "yyyy-MM" 格式化
        //2.找预算行
        DeptBudget budget = deptBudgetMapper.selectByDeptPeriod(report.getApplicantDeptId(), fiscalYear, period);
        if(budget == null){
            throw new BizException(ErrorCode.BUDGET_NOT_ENOUGH,"部门未配置预算");
        }
        //3.原子更新
        int row = deptBudgetMapper.update(null, new LambdaUpdateWrapper<DeptBudget>()
                .eq(DeptBudget::getId, budget.getId())
                .apply("total_amount - used_amount - frozen_amount >= {0}", report.getAmountTotal())
                .setSql("frozen_amount = frozen_amount + {0}", report.getAmountTotal())
        );
        if(row == 0){
            throw new BizException(ErrorCode.BUDGET_NOT_ENOUGH,"部门预算不足,可用额度：" + budget.getTotalAmount().subtract(budget.getUsedAmount()).subtract(budget.getFrozenAmount()));
        }
        //4.计入流水
        writeBudgetTxn(budget,report);

        return deptBudgetMapper.selectById(budget.getId());
    }

    private void writeBudgetTxn(DeptBudget budget, ExpenseReport report) {
        BudgetTxn txn = new BudgetTxn();
        txn.setBudgetId(budget.getId());
        txn.setReportId(report.getId());
        txn.setBizNo("FREEZE:"+report.getId()+":"+report.getSubmitToken());
        txn.setTxnType("FREEZE");
        txn.setAmount(report.getAmountTotal());

        try {
            budgetTxnMapper.insert(txn);
        }catch (DuplicateKeyException e){
            throw new BizException(ErrorCode.STATE_ILLEGAL,"预算已冻结");
        }
    }

    @Override
    @Transactional
    public void release(ExpenseReport report) {
        // 1. 历史单/未冻结的单直接跳过，释放必须可空转
        if (report == null || report.getBudgetId() == null) {
            return;
        }
        // 2. 幂等：同一 submitToken 的释放只做一次（重复点击/重试）
        String bizNo = "RELEASE:" + report.getId() + ":" + report.getSubmitToken();
        if (budgetTxnMapper.selectByBizNo(bizNo) != null) {
            return;
        }
        // 3. 条件更新：frozen 足够才释放，防止减成负数
        int row = deptBudgetMapper.update(null, new LambdaUpdateWrapper<DeptBudget>()
                .eq(DeptBudget::getId, report.getBudgetId())
                .ge(DeptBudget::getFrozenAmount, report.getAmountTotal())
                .setSql("frozen_amount = frozen_amount - {0}", report.getAmountTotal()));
        if (row == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "预算数据不一致，释放失败");
        }
        // 4. 记流水
        BudgetTxn txn = new BudgetTxn();
        txn.setBudgetId(report.getBudgetId());
        txn.setReportId(report.getId());
        txn.setBizNo(bizNo);
        txn.setTxnType("RELEASE");
        txn.setAmount(report.getAmountTotal());
        budgetTxnMapper.insert(txn);
    }

    @Override
    @Transactional
    public void deduct(ExpenseReport report) {
        // 1. 历史单/未冻结的单直接跳过，扣减必须可空转
        if (report == null || report.getBudgetId() == null) {
            return;
        }
        // 2. 幂等：T4 是终态，一单只会扣一次，幂等键不需要 submitToken
        String bizNo = "DEDUCT:" + report.getId();
        if (budgetTxnMapper.selectByBizNo(bizNo) != null) {
            return;
        }
        // 3. 条件更新：一条 SQL 同时改两个字段，保证"冻结转实扣"原子
        int row = deptBudgetMapper.update(null, new LambdaUpdateWrapper<DeptBudget>()
                .eq(DeptBudget::getId, report.getBudgetId())
                .ge(DeptBudget::getFrozenAmount, report.getAmountTotal())
                .setSql("used_amount = used_amount + {0}", report.getAmountTotal())
                .setSql("frozen_amount = frozen_amount - {0}", report.getAmountTotal()));
        if (row == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "预算数据不一致，扣减失败");
        }
        //4.计入流水
        BudgetTxn txn = new BudgetTxn();
        txn.setBudgetId(report.getBudgetId());
        txn.setReportId(report.getId());
        txn.setBizNo(bizNo);
        txn.setTxnType("DEDUCT");
        txn.setAmount(report.getAmountTotal());
        budgetTxnMapper.insert(txn);
    }

    @Override
    public List<BudgetVO> listBudgets(String period) {
        String p = normalizePeriod(period);
        List<BudgetVO> list = deptBudgetMapper.selectBudgetList(p);
        for (BudgetVO vo : list) {
            fillComputed(vo);
        }
        return list;
    }

    @Override
    @Transactional
    public BudgetVO adjustBudget(Long deptId, String period, BigDecimal totalAmount) {
        return adjustBudget(deptId, period, totalAmount, "SET");
    }

    @Override
    @Transactional
    public BudgetVO adjustBudget(Long deptId, String period, BigDecimal totalAmount, String mode) {
        String p = normalizePeriod(period);
        String m = mode == null || mode.isBlank() ? "SET" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"SET".equals(m) && !"ADD".equals(m)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "mode 只能是 SET 或 ADD");
        }
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "预算金额不能为负数");
        }
        SysDept dept = sysDeptMapper.selectById(deptId);
        if (dept == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "部门不存在");
        }

        short year = Short.parseShort(p.substring(0, 4));
        DeptBudget budget = deptBudgetMapper.selectByDeptPeriod(deptId, year, p);
        BigDecimal currentTotal = budget == null ? BigDecimal.ZERO : budget.getTotalAmount();
        // 先统一成"本部门新总额"再做总池校验，两种模式共用同一套约束
        BigDecimal newTotal = "ADD".equals(m) ? currentTotal.add(totalAmount) : totalAmount;

        // 总预算池约束：本部门新总额 + 其他部门已分配 ≤ 总预算（未设置池时不限制）
        BudgetPool pool = budgetPoolMapper.selectByPeriod(p);
        if (pool != null) {
            BigDecimal others = deptBudgetMapper.selectBudgetList(p).stream()
                    .filter(vo -> !vo.getDeptId().equals(deptId))
                    .map(BudgetVO::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal limit = pool.getTotalAmount().subtract(others);
            if (newTotal.compareTo(limit) > 0) {
                throw new BizException(ErrorCode.STATE_ILLEGAL,
                        "超出总预算，本部门最多可分配 " + limit.toPlainString());
            }
        }

        if (budget == null) {
            // 首次分发预算：新建行，used/frozen 从 0 起
            budget = new DeptBudget();
            budget.setDeptId(deptId);
            budget.setFiscalYear(year);
            budget.setPeriod(p);
            budget.setTotalAmount(newTotal);
            budget.setUsedAmount(BigDecimal.ZERO);
            budget.setFrozenAmount(BigDecimal.ZERO);
            budget.setVersion(0);
            deptBudgetMapper.insert(budget);
        } else {
            // 总额不能低于已用 + 冻结：DB 有 CHECK 约束，这里先给出可读错误
            BigDecimal locked = budget.getUsedAmount().add(budget.getFrozenAmount());
            if (newTotal.compareTo(locked) < 0) {
                throw new BizException(ErrorCode.STATE_ILLEGAL,
                        "总额不能低于已用+冻结（" + locked.toPlainString() + "）");
            }
            int rows = deptBudgetMapper.update(null, new LambdaUpdateWrapper<DeptBudget>()
                    .eq(DeptBudget::getId, budget.getId())
                    .set(DeptBudget::getTotalAmount, newTotal));
            if (rows == 0) {
                throw new BizException(ErrorCode.STATE_ILLEGAL, "预算行更新失败，请重试");
            }
        }

        // 调整也进流水：ADD 记增量，SET 记本次变化量（可能为负）。
        // 对账只认 FREEZE/DEDUCT/RELEASE，ADJUST 仅用于审计回放（docs/06 §2.2）。
        BigDecimal delta = "ADD".equals(m) ? totalAmount : newTotal.subtract(currentTotal);
        writeAdjustTxn(budget.getId(), m, delta);

        return deptBudgetMapper.selectBudgetList(p).stream()
                .filter(vo -> vo.getDeptId().equals(deptId))
                .findFirst()
                .map(vo -> {
                    fillComputed(vo);
                    return vo;
                })
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "预算行回读失败"));
    }

    /** 预算调整流水：biz_no 用 UUID，一次调整一条；操作本身不可重放，不做幂等回放 */
    private void writeAdjustTxn(Long budgetId, String mode, BigDecimal delta) {
        BudgetTxn txn = new BudgetTxn();
        txn.setBudgetId(budgetId);
        txn.setReportId(null);
        txn.setBizNo("ADJUST:" + budgetId + ":" + UUID.randomUUID());
        txn.setTxnType("ADD".equals(mode) ? "ADJUST_ADD" : "ADJUST");
        txn.setAmount(delta);
        budgetTxnMapper.insert(txn);
    }

    @Override
    public BudgetPoolVO getPool(String period) {
        String p = normalizePeriod(period);
        BudgetPool pool = budgetPoolMapper.selectByPeriod(p);
        BigDecimal allocated = deptBudgetMapper.selectBudgetList(p).stream()
                .map(BudgetVO::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BudgetPoolVO vo = new BudgetPoolVO();
        vo.setPeriod(p);
        vo.setTotalAmount(pool == null ? null : pool.getTotalAmount());
        vo.setAllocatedAmount(allocated);
        vo.setUnallocatedAmount(pool == null ? null : pool.getTotalAmount().subtract(allocated));
        return vo;
    }

    @Override
    @Transactional
    public BudgetPoolVO setPool(String period, BigDecimal totalAmount) {
        String p = normalizePeriod(period);
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "总预算不能为负数");
        }
        // 不能低于已分配：否则部门预算之和立刻超池，后续校验会全部报错
        BigDecimal allocated = deptBudgetMapper.selectBudgetList(p).stream()
                .map(BudgetVO::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalAmount.compareTo(allocated) < 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL,
                    "总预算不能低于已分配（" + allocated.toPlainString() + "）");
        }
        BudgetPool pool = budgetPoolMapper.selectByPeriod(p);
        if (pool == null) {
            pool = new BudgetPool();
            pool.setPeriod(p);
            pool.setTotalAmount(totalAmount);
            budgetPoolMapper.insert(pool);
        } else {
            budgetPoolMapper.update(null, new LambdaUpdateWrapper<BudgetPool>()
                    .eq(BudgetPool::getId, pool.getId())
                    .set(BudgetPool::getTotalAmount, totalAmount));
        }
        return getPool(p);
    }

    /** 可用额度与使用率在服务端算好，前端只负责展示（避免两边口径漂移） */
    private void fillComputed(BudgetVO vo) {
        BigDecimal total = vo.getTotalAmount() == null ? BigDecimal.ZERO : vo.getTotalAmount();
        BigDecimal used = vo.getUsedAmount() == null ? BigDecimal.ZERO : vo.getUsedAmount();
        BigDecimal frozen = vo.getFrozenAmount() == null ? BigDecimal.ZERO : vo.getFrozenAmount();
        vo.setAvailableAmount(total.subtract(used).subtract(frozen));
        vo.setUsageRate(total.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : used.add(frozen).divide(total, 4, RoundingMode.HALF_UP));
    }

    /** 账期统一成 yyyy-MM；空值默认当前月 */
    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        if (!period.matches("\\d{4}-\\d{2}")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "账期格式应为 YYYY-MM");
        }
        return period;
    }
}
