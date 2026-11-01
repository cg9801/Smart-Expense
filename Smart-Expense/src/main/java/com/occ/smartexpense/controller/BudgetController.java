package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.BudgetAdjustDTO;
import com.occ.smartexpense.dto.BudgetPoolDTO;
import com.occ.smartexpense.security.RequireRole;
import com.occ.smartexpense.service.BudgetService;
import com.occ.smartexpense.vo.BudgetPoolVO;
import com.occ.smartexpense.vo.BudgetVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门预算接口：财务/管理员可查，仅管理员可调整（分发预算）。
 * 契约：docs/03-接口清单.md §2.4 预算查询；口径：docs/06 §2.1。
 */
@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
@RequireRole({"FINANCE", "ADMIN"})
public class BudgetController {

    private final BudgetService budgetService;

    /** 某账期全部部门预算（管理总览/预算管理），period 为空默认当前月 */
    @GetMapping
    public Result<List<BudgetVO>> listBudgets(@RequestParam(required = false) String period) {
        return Result.ok(budgetService.listBudgets(period));
    }

    /** 调整（分发/追加）部门预算总额，方法级注解覆盖类级：仅 ADMIN；mode=SET 覆盖 / ADD 追加 */
    @PutMapping("/{deptId}")
    @RequireRole({"ADMIN"})
    public Result<BudgetVO> adjustBudget(@PathVariable Long deptId,
                                         @Valid @RequestBody BudgetAdjustDTO dto) {
        return Result.ok(budgetService.adjustBudget(deptId, dto.getPeriod(), dto.getTotalAmount(), dto.getMode()));
    }

    /** 公司总预算池（管理端"设置总预算"），period 为空默认当前月 */
    @GetMapping("/pool")
    public Result<BudgetPoolVO> getPool(@RequestParam(required = false) String period) {
        return Result.ok(budgetService.getPool(period));
    }

    /** 设置总预算：不得低于已分配，仅 ADMIN */
    @PutMapping("/pool")
    @RequireRole({"ADMIN"})
    public Result<BudgetPoolVO> setPool(@Valid @RequestBody BudgetPoolDTO dto) {
        return Result.ok(budgetService.setPool(dto.getPeriod(), dto.getTotalAmount()));
    }
}
