package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseItem;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.Invoice;
import com.occ.smartexpense.mapper.ExpenseItemMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.InvoiceMapper;
import com.occ.smartexpense.vo.ReportDetailVO;
import com.occ.smartexpense.vo.ReportVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除草稿（DELETE /reports/{id}）集成测试，对应 docs/03 §2.2 与 docs/02 §3 补记。
 * 删除不是状态流转：逻辑删除 deleted=1 + 明细清理 + 发票解绑，不写审计。
 */
@SpringBootTest
@Transactional
class ReportDeleteTest {

    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Autowired
    private ExpenseItemMapper itemMapper;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void delete_draft_removes_it_from_detail_and_list() {
        ReportDetailVO draft = createDraft();
        Long id = draft.getId();

        reportService.deleteDraft(id, APPLICANT_ID);

        // 详情 404：@TableLogic 让 selectById 自动带 deleted=0
        BizException ex = assertThrows(BizException.class, () -> reportService.getDetail(id));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        // 原始行仍在库中，只是 deleted=1（逻辑删除，可追溯）
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM expense_report WHERE id = ?", Integer.class, id);
        assertEquals(1, deletedFlag);

        // 明细已清理
        Long items = itemMapper.selectCount(new LambdaQueryWrapper<ExpenseItem>()
                .eq(ExpenseItem::getReportId, id));
        assertEquals(0L, items);

        // 列表不再出现
        List<ReportVO> mine = reportService.listReports(APPLICANT_ID, APPLICANT_DEPT_ID,
                Set.of("EMPLOYEE"), "mine", null, null, 1, 100).getRecords();
        assertTrue(mine.stream().noneMatch(r -> id.equals(r.getId())));
    }

    @Test
    void delete_by_other_user_is_forbidden() {
        ReportDetailVO draft = createDraft();

        BizException ex = assertThrows(BizException.class,
                () -> reportService.deleteDraft(draft.getId(), 2L));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        // 失败不产生副作用
        assertEquals("DRAFT", reportMapper.selectById(draft.getId()).getStatus());
    }

    @Test
    void delete_submitted_report_is_state_illegal() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), "idem-delete-submitted", null);

        BizException ex = assertThrows(BizException.class,
                () -> reportService.deleteDraft(draft.getId(), APPLICANT_ID));
        assertEquals(ErrorCode.STATE_ILLEGAL, ex.getErrorCode());
        assertEquals("PENDING", reportMapper.selectById(draft.getId()).getStatus());
    }

    @Test
    void delete_draft_unbinds_invoice() {
        ReportDetailVO draft = createDraft();
        Invoice invoice = new Invoice();
        invoice.setFileUrl("202610/test.png");
        invoice.setFileHash("hash-" + System.nanoTime());
        invoice.setOriginalName("test.png");
        invoice.setOcrStatus("MANUAL");
        invoice.setRetryCount(0);
        invoice.setUploaderId(APPLICANT_ID);
        invoice.setReportId(draft.getId());
        invoiceMapper.insert(invoice);

        reportService.deleteDraft(draft.getId(), APPLICANT_ID);

        Invoice after = invoiceMapper.selectById(invoice.getId());
        assertNull(after.getReportId());
    }

    /** 建一张合法草稿：明细 1 条 3200.00 */
    private ReportDetailVO createDraft() {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 10, 18));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("北京-上海 高铁往返");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setApplicantId(APPLICANT_ID);
        dto.setApplicantDeptId(APPLICANT_DEPT_ID);
        dto.setTitle("删除草稿测试");
        dto.setExpenseType("TRAVEL");
        dto.setRemark("ReportDeleteTest");
        dto.setItems(List.of(item));
        return reportService.createDraft(dto);
    }
}
