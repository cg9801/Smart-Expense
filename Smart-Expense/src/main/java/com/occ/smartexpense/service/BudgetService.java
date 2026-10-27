package com.occ.smartexpense.service;

import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.ExpenseReport;

/**
 * 预算动作，完整流程见 docs/06-预算冻结与扣减.md。
 * 三个方法都在调用方事务里执行（propagation = REQUIRED），失败与调用方一起回滚。
 */
public interface BudgetService {

    /**
     * 提交时冻结额度（docs/06 §4.1）。
     * 返回命中的预算行，调用方用它回写 expense_report.budget_id / budget_period。
     *
     * @throws com.occ.smartexpense.common.BizException 40902 预算未配置或可用额度不足
     */
    DeptBudget freeze(ExpenseReport report);

    /**
     * 撤回 / 驳回时释放额度（docs/06 §4.2）。
     * report.budgetId 为 null 时直接返回（兼容预算功能上线前的历史单）。
     */
    void release(ExpenseReport report);

    /**
     * 末级通过时冻结转实扣（docs/06 §4.3）。
     * report.budgetId 为 null 时直接返回；frozen 不足则抛 40901 并回滚整个审批事务。
     */
    void deduct(ExpenseReport report);
}
