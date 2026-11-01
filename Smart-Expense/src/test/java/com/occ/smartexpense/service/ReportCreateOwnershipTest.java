package com.occ.smartexpense.service;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.entity.Invoice;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.mapper.InvoiceMapper;
import com.occ.smartexpense.vo.ReportDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 建单身份只认服务端（docs/12 §6、docs/03 §2.2）：
 * DTO 里已经没有 applicantId/deptId，申请人来自登录上下文；发票绑定也只能用本人上传的。
 */
@SpringBootTest
@Transactional
class ReportCreateOwnershipTest {

    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;
    private static final long OTHER_USER = 3L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Test
    void identity_comes_from_context_not_from_body() {
        ReportDetailVO detail = reportService.createDraft(dto(), APPLICANT_ID, APPLICANT_DEPT_ID);

        ExpenseReport saved = reportMapper.selectById(detail.getId());
        assertEquals(APPLICANT_ID, saved.getApplicantId());
        assertEquals(APPLICANT_DEPT_ID, saved.getApplicantDeptId());
    }

    @Test
    void missing_department_is_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> reportService.createDraft(dto(), APPLICANT_ID, null));
        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
    }

    @Test
    void cannot_bind_invoice_uploaded_by_someone_else() {
        Invoice other = new Invoice();
        other.setFileUrl("202611/other.png");
        other.setFileHash("hash-other-" + System.nanoTime());
        other.setOriginalName("other.png");
        other.setOcrStatus("MANUAL");
        other.setRetryCount(0);
        other.setUploaderId(OTHER_USER);
        invoiceMapper.insert(other);

        ReportCreateDTO dto = dto();
        dto.getItems().get(0).setInvoiceId(other.getId());

        BizException ex = assertThrows(BizException.class,
                () -> reportService.createDraft(dto, APPLICANT_ID, APPLICANT_DEPT_ID));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals("只能关联本人上传的发票", ex.getMessage());
    }

    private ReportCreateDTO dto() {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 11, 1));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("建单身份测试");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setTitle("建单身份测试");
        dto.setExpenseType("TRAVEL");
        dto.setRemark("ReportCreateOwnershipTest");
        dto.setItems(List.of(item));
        return dto;
    }
}
