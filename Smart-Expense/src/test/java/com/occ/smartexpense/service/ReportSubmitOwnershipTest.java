package com.occ.smartexpense.service;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.vo.ReportDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 提交入口的归属校验（docs/03 §2.2、docs/02 §3 T1）：
 * 只有申请人本人能提交；他人提交必须 40301 且不产生任何副作用（状态、幂等 token、审批链）。
 */
@SpringBootTest
@Transactional
class ReportSubmitOwnershipTest {

    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;
    private static final long OTHER_USER = 3L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Test
    void others_cannot_submit_my_draft() {
        ReportDetailVO draft = createDraft();

        BizException ex = assertThrows(BizException.class,
                () -> reportService.submitReport(draft.getId(), OTHER_USER, "idem-other-user", null));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());

        ExpenseReport after = reportMapper.selectById(draft.getId());
        assertEquals("DRAFT", after.getStatus());
        assertNull(after.getSubmitToken(), "被拒绝的提交不能写幂等 token");
    }

    @Test
    void owner_can_submit() {
        ReportDetailVO draft = createDraft();

        ReportDetailVO submitted = reportService.submitReport(draft.getId(), APPLICANT_ID, "idem-owner", null);
        assertEquals("PENDING", submitted.getStatus());
    }

    private ReportDetailVO createDraft() {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 11, 1));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("提交归属测试");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setTitle("提交归属测试");
        dto.setExpenseType("TRAVEL");
        dto.setRemark("ReportSubmitOwnershipTest");
        dto.setItems(List.of(item));
        return reportService.createDraft(dto, APPLICANT_ID, APPLICANT_DEPT_ID);
    }
}
