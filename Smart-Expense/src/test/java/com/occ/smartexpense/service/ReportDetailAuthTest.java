package com.occ.smartexpense.service;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.vo.ReportDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 详情可见范围（GET /reports/{id}，docs/03 §2.2）：
 * 本人 / 该单审批人 / 财务管理员 / 同部门主管可见，其余一律 40301。
 */
@SpringBootTest
@Transactional
class ReportDetailAuthTest {

    private static final long LINYI = 1L;
    private static final long ZHANGSAN = 2L;
    private static final long LISI = 3L;
    private static final long WANGWU = 4L;
    private static final long DEPT_TECH = 2L;
    private static final long DEPT_MARKET = 3L;

    @Autowired
    private ReportService reportService;

    @Test
    void applicant_can_view_own_report() {
        Long id = draft(LINYI, DEPT_TECH).getId();
        assertEquals(id, reportService.getDetailFor(id, LINYI, DEPT_TECH, Set.of("EMPLOYEE")).getId());
    }

    @Test
    void other_employee_cannot_view() {
        Long id = draft(LINYI, DEPT_TECH).getId();
        BizException ex = assertThrows(BizException.class,
                () -> reportService.getDetailFor(id, LISI, DEPT_MARKET, Set.of("EMPLOYEE")));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void approver_can_view_after_submit() {
        Long id = draft(LINYI, DEPT_TECH).getId();
        reportService.submitReport(id, LINYI, "idem-auth-approver", null);
        // 张三是一级审批人；即便去掉"同部门主管"这条，审批人身份也应放行
        assertEquals(id, reportService.getDetailFor(id, ZHANGSAN, DEPT_TECH, Set.of("SUPERVISOR")).getId());
    }

    @Test
    void finance_can_view_any_report() {
        Long id = draft(LINYI, DEPT_TECH).getId();
        assertEquals(id, reportService.getDetailFor(id, WANGWU, 1L, Set.of("FINANCE")).getId());
    }

    @Test
    void supervisor_can_view_same_dept_draft() {
        Long id = draft(LINYI, DEPT_TECH).getId();
        // 草稿没有审批链，只能靠"同部门主管"这条放行
        assertEquals(id, reportService.getDetailFor(id, ZHANGSAN, DEPT_TECH, Set.of("SUPERVISOR")).getId());
    }

    @Test
    void supervisor_cannot_view_other_dept() {
        Long id = draft(LISI, DEPT_MARKET).getId();
        BizException ex = assertThrows(BizException.class,
                () -> reportService.getDetailFor(id, ZHANGSAN, DEPT_TECH, Set.of("SUPERVISOR")));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void missing_report_is_not_found() {
        BizException ex = assertThrows(BizException.class,
                () -> reportService.getDetailFor(999_999_999L, LINYI, DEPT_TECH, Set.of("EMPLOYEE")));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    private ReportDetailVO draft(long applicantId, long deptId) {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 10, 18));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("北京-上海 高铁往返");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setTitle("详情鉴权测试");
        dto.setExpenseType("TRAVEL");
        dto.setRemark("ReportDetailAuthTest");
        dto.setItems(List.of(item));
        return reportService.createDraft(dto, applicantId, deptId);
    }
}
