package com.occ.smartexpense.rule;

import com.occ.smartexpense.entity.DeptBudget;
import com.occ.smartexpense.entity.ExpenseItem;
import com.occ.smartexpense.entity.ExpenseReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 硬规则引擎单元测试，见 docs/11-AI识别与辅助审核.md §2.1。
 *
 * <p>纯函数的好处：不起 Spring、不连库，每条规则毫秒级验完。
 */
class HardRuleEngineTest {

    private final HardRuleEngine engine = new HardRuleEngine();

    // ---------- 正常路径 ----------

    @Test
    void allGood_returnsNoFindings() {
        ExpenseReport report = report("10 月差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.00"));
        DeptBudget budget = budget("50000.00", "0.00", "0.00");

        assertTrue(engine.evaluate(report, items, budget).isEmpty());
    }

    // ---------- 金额一致性 ----------

    @Test
    void amountMismatch_isDetected() {
        ExpenseReport report = report("差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1200.00"));

        List<HardFinding> findings = engine.evaluate(report, items, null);

        assertEquals(1, findings.size());
        assertEquals(HardRuleEngine.AMOUNT_MISMATCH, findings.get(0).code());
        assertEquals(Severity.HIGH, findings.get(0).severity());
    }

    @Test
    void amountEqualWithDifferentScale_isNotMismatch() {
        // 1500.00 与 1500.0 精度不同但金额相等：compareTo 为 0，equals 才是 false
        ExpenseReport report = report("差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.0"));

        assertTrue(engine.evaluate(report, items, null).isEmpty());
    }

    // ---------- 预算 ----------

    @Test
    void budgetExceeded_isDetected() {
        ExpenseReport report = report("差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.00"));
        DeptBudget budget = budget("2000.00", "500.00", "700.00");   // 可用 800

        List<HardFinding> findings = engine.evaluate(report, items, budget);

        assertEquals(1, findings.size());
        assertEquals(HardRuleEngine.BUDGET_EXCEEDED, findings.get(0).code());
    }

    @Test
    void budgetNull_skipsRule() {
        ExpenseReport report = report("差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.00"));

        assertTrue(engine.evaluate(report, items, null).isEmpty(), "没有预算记录不算超预算");
    }

    // ---------- 明细金额 ----------

    @Test
    void invalidItemAmounts_areSummarizedIntoOneFinding() {
        ExpenseReport report = report("差旅", "TRAVEL", "0.00");
        List<ExpenseItem> items = List.of(
                item(1, "-10.00"),
                item(2, "0"),
                item(3, null),
                item(4, "0.00"));

        List<HardFinding> findings = engine.evaluate(report, items, null);

        long itemFindings = findings.stream()
                .filter(f -> HardRuleEngine.ITEM_AMOUNT_INVALID.equals(f.code()))
                .count();
        assertEquals(1, itemFindings, "同一规则只能产出一条汇总");
        HardFinding finding = findings.stream()
                .filter(f -> HardRuleEngine.ITEM_AMOUNT_INVALID.equals(f.code()))
                .findFirst()
                .orElseThrow();
        assertTrue(finding.message().contains("4"), "汇总里要带条数");
    }

    // ---------- 必填字段 ----------

    @Test
    void missingTitle_isDetected() {
        ExpenseReport report = report("  ", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.00"));

        List<HardFinding> findings = engine.evaluate(report, items, null);

        assertEquals(1, findings.size());
        assertEquals(HardRuleEngine.MISSING_REQUIRED_FIELD, findings.get(0).code());
        assertEquals(Severity.LOW, findings.get(0).severity());
    }

    @Test
    void emptyItems_reportsMissingDetail() {
        ExpenseReport report = report("差旅", "TRAVEL", "0.00");

        List<HardFinding> findings = engine.evaluate(report, List.of(), null);

        assertTrue(findings.stream()
                .anyMatch(f -> HardRuleEngine.MISSING_REQUIRED_FIELD.equals(f.code())));
        assertTrue(findings.stream()
                .anyMatch(f -> f.message().contains("明细")));
    }

    // ---------- 发票查重（docs/19 §4）----------

    @Test
    void duplicateFileFromOtherUploader_isDetected() {
        ExpenseReport report = report("差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.00"));
        InvoiceFacts facts = new InvoiceFacts(1, 1, 0);

        List<HardFinding> findings = engine.evaluate(report, items, null, facts);

        HardFinding finding = findings.stream()
                .filter(f -> HardRuleEngine.DUPLICATE_FILE_SUSPECT.equals(f.code()))
                .findFirst().orElseThrow();
        assertEquals(Severity.HIGH, finding.severity());
        assertTrue(finding.message().contains("1"));
    }

    @Test
    void duplicateInvoiceNoReuse_isDetected() {
        ExpenseReport report = report("差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.00"));
        InvoiceFacts facts = new InvoiceFacts(1, 0, 2);

        List<HardFinding> findings = engine.evaluate(report, items, null, facts);

        assertTrue(findings.stream()
                .anyMatch(f -> HardRuleEngine.DUPLICATE_INVOICE_SUSPECT.equals(f.code())));
    }

    @Test
    void invoiceFactsWithoutInvoices_producesNothing() {
        ExpenseReport report = report("差旅", "TRAVEL", "1500.00");
        List<ExpenseItem> items = List.of(item(1, "1500.00"));

        // 没有发票时，即使事实里带计数也不该产出（调用方传 0 是正常，传脏数据也不阻断）
        assertTrue(engine.evaluate(report, items, null, new InvoiceFacts(0, 3, 3)).isEmpty());
    }

    // ---------- 兜底 ----------

    @Test
    void nullReport_returnsEmptyInsteadOfThrowing() {
        assertTrue(engine.evaluate(null, List.of(), null).isEmpty(), "引擎不阻断、不抛异常");
    }

    // ---------- 测试夹具 ----------

    private ExpenseReport report(String title, String expenseType, String amountTotal) {
        ExpenseReport report = new ExpenseReport();
        report.setTitle(title);
        report.setExpenseType(expenseType);
        report.setAmountTotal(new BigDecimal(amountTotal));
        return report;
    }

    private ExpenseItem item(int sortNo, String amount) {
        ExpenseItem item = new ExpenseItem();
        item.setSortNo(sortNo);
        item.setAmount(amount == null ? null : new BigDecimal(amount));
        return item;
    }

    private DeptBudget budget(String total, String used, String frozen) {
        DeptBudget budget = new DeptBudget();
        budget.setTotalAmount(new BigDecimal(total));
        budget.setUsedAmount(new BigDecimal(used));
        budget.setFrozenAmount(new BigDecimal(frozen));
        return budget;
    }
}