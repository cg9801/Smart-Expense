package com.occ.smartexpense.rule;

import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.ExpenseItem;
import com.occ.smartexpense.entity.ExpenseReport;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 硬规则引擎，见 docs/11-AI识别与辅助审核.md §2.1。
 *
 * <p><b>纯函数</b>：入参是调用方已经查好的数据，不注入 Mapper、不查库——规则是系统里
 * 唯一"输入确定、输出确定"的部分，必须能被毫秒级单测钉死。
 *
 * <p><b>只产出、不阻断</b>：结果同时进 Agent 初始上下文（§5.3）和 {@code ai_review.hard_findings}，
 * 任何情况下都不改单据状态、不抛业务异常。
 *
 * <p>发票类规则（{@code DUPLICATE_*}）依赖调用方查好的 {@link InvoiceFacts}，见 docs/19 §4。
 */
@Component
public class HardRuleEngine {

    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String ITEM_AMOUNT_INVALID = "ITEM_AMOUNT_INVALID";
    public static final String MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";
    public static final String DUPLICATE_FILE_SUSPECT = "DUPLICATE_FILE_SUSPECT";
    public static final String DUPLICATE_INVOICE_SUSPECT = "DUPLICATE_INVOICE_SUSPECT";

    /**
     * 跑一遍全部规则（兼容旧调用：不传发票事实时跳过发票类规则）。
     *
     * @param report 单据（为空时直接返回空结果，不阻断）
     * @param items  明细（为空视同"没有明细行"）
     * @param budget 单据冻结的部门预算（为空表示没有预算记录，跳过该规则）
     */
    public List<HardFinding> evaluate(ExpenseReport report, List<ExpenseItem> items, DeptBudget budget) {
        return evaluate(report, items, budget, null);
    }

    /**
     * 带发票事实的全量规则。
     *
     * @param facts 发票查重事实；为空表示没有发票上下文，跳过发票类规则
     */
    public List<HardFinding> evaluate(ExpenseReport report, List<ExpenseItem> items,
                                      DeptBudget budget, InvoiceFacts facts) {
        if (report == null) {
            return List.of();
        }
        List<ExpenseItem> safeItems = items == null ? List.of() : items;
        List<HardFinding> findings = new ArrayList<>();

        checkItemAmounts(safeItems, findings);
        checkAmountTotal(report, safeItems, findings);
        checkBudget(report, budget, findings);
        checkRequiredFields(report, safeItems, findings);
        checkDuplicateInvoices(facts, findings);

        return findings;
    }

    /** 明细金额必须 > 0；同一规则只汇总一条，不逐行刷屏 */
    private void checkItemAmounts(List<ExpenseItem> items, List<HardFinding> findings) {
        List<String> badLines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BigDecimal amount = items.get(i).getAmount();
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                badLines.add(String.valueOf(lineNo(items.get(i), i)));
            }
        }
        if (!badLines.isEmpty()) {
            findings.add(new HardFinding(ITEM_AMOUNT_INVALID, Severity.HIGH,
                    "共 " + badLines.size() + " 条明细金额非法（第 " + String.join("、", badLines) + " 行）"));
        }
    }

    /** 明细合计与单据金额必须一致；用 compareTo 比较，避免 1500.00 与 1500.0 被判不等 */
    private void checkAmountTotal(ExpenseReport report, List<ExpenseItem> items, List<HardFinding> findings) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ExpenseItem item : items) {
            sum = sum.add(nz(item.getAmount()));
        }
        BigDecimal total = nz(report.getAmountTotal());
        if (sum.compareTo(total) != 0) {
            findings.add(new HardFinding(AMOUNT_MISMATCH, Severity.HIGH,
                    "明细合计 " + sum.toPlainString() + " 与单据金额 " + total.toPlainString() + " 不一致"));
        }
    }

    /** 可用额度 = total - used - frozen，见 docs/06 §2.1 */
    private void checkBudget(ExpenseReport report, DeptBudget budget, List<HardFinding> findings) {
        if (budget == null) {
            return;   // 没有预算记录不等于超预算；提交时已由 budgetService.freeze 把关
        }
        BigDecimal available = nz(budget.getTotalAmount())
                .subtract(nz(budget.getUsedAmount()))
                .subtract(nz(budget.getFrozenAmount()));
        BigDecimal total = nz(report.getAmountTotal());
        if (total.compareTo(available) > 0) {
            findings.add(new HardFinding(BUDGET_EXCEEDED, Severity.HIGH,
                    "预算可用 " + available.toPlainString() + "，本单金额 " + total.toPlainString()));
        }
    }

    /** 必填字段缺失；多个字段合并成一条提示 */
    private void checkRequiredFields(ExpenseReport report, List<ExpenseItem> items, List<HardFinding> findings) {
        List<String> missing = new ArrayList<>();
        if (report.getTitle() == null || report.getTitle().isBlank()) {
            missing.add("标题");
        }
        if (report.getExpenseType() == null || report.getExpenseType().isBlank()) {
            missing.add("费用类型");
        }
        if (items.isEmpty()) {
            missing.add("明细");
        }
        if (!missing.isEmpty()) {
            findings.add(new HardFinding(MISSING_REQUIRED_FIELD, Severity.LOW,
                    "缺少必填字段：" + String.join("、", missing)));
        }
    }

    /** 发票查重（docs/19 §4）：同一规则只产出一条，不按发票逐条刷屏 */
    private void checkDuplicateInvoices(InvoiceFacts facts, List<HardFinding> findings) {
        if (facts == null || facts.invoiceCount() <= 0) {
            return;
        }
        if (facts.duplicateFileOtherUploaders() > 0) {
            findings.add(new HardFinding(DUPLICATE_FILE_SUSPECT, Severity.HIGH,
                    "发票文件与其他人上传的重复 " + facts.duplicateFileOtherUploaders()
                            + " 次，疑似一票多报"));
        }
        if (facts.duplicateInvoiceNoReuse() > 0) {
            findings.add(new HardFinding(DUPLICATE_INVOICE_SUSPECT, Severity.HIGH,
                    "发票号码已被其他报销单使用 " + facts.duplicateInvoiceNoReuse() + " 次"));
        }
    }

    private static int lineNo(ExpenseItem item, int index) {
        return item.getSortNo() == null ? index + 1 : item.getSortNo();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}