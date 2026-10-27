package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.entity.BudgetTxn;
import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.mapper.BudgetTxnMapper;
import com.occ.smartexpense.mapper.DeptBudgetMapper;
import com.occ.smartexpense.service.BudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 预算动作实现，步骤与 docs/06-预算冻结与扣减.md §4 一一对应。
 * 骨架阶段：先让编译通过，逻辑按文档 §8 的落地顺序逐个补。
 */
@Service
@RequiredArgsConstructor
public class BudgetServiceImpl implements BudgetService {

    private final DeptBudgetMapper deptBudgetMapper;
    private final BudgetTxnMapper budgetTxnMapper;

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
}
