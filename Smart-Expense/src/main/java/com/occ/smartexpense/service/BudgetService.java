package com.occ.smartexpense.service;

import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.vo.BudgetPoolVO;
import com.occ.smartexpense.vo.BudgetVO;

import java.math.BigDecimal;
import java.util.List;

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

    /**
     * 某账期全部部门预算（管理端总览/预算管理页），period 为空用当前月。
     */
    List<BudgetVO> listBudgets(String period);

    /**
     * 调整部门预算总额（分发/追加），admin 专用。
     * 守卫：总额不能低于已用 + 冻结（DB 有 CHECK 约束，这里提前给出可读错误）。
     */
    BudgetVO adjustBudget(Long deptId, String period, BigDecimal totalAmount);

    /**
     * 带模式的调整：SET 覆盖（默认）/ ADD 追加入账。
     * ADD 时 totalAmount 是增量；两种模式都写预算流水的审计记录。
     */
    BudgetVO adjustBudget(Long deptId, String period, BigDecimal totalAmount, String mode);

    /** 公司总预算池（管理端"设置总预算"）；未设置时 totalAmount 为 null */
    BudgetPoolVO getPool(String period);

    /** 设置总预算池：不得低于已分配（各部门 total 之和），否则 409 */
    BudgetPoolVO setPool(String period, BigDecimal totalAmount);
}
