package com.occ.smartexpense.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.entity.AiReview;
import com.occ.smartexpense.entity.ExpenseReport;
import com.occ.smartexpense.mapper.AiReviewMapper;
import com.occ.smartexpense.mapper.ExpenseReportMapper;
import com.occ.smartexpense.vo.AiAdviceVO;
import com.occ.smartexpense.vo.ReportDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 建议手动重算（POST /admin/ai-review/refresh）测试，对应 docs/03 §2.7。
 * 只验证"同一版本重新算一次、库里只留一条"，不依赖模型质量（默认 provider=mock）。
 */
@SpringBootTest
@Transactional
class AiReviewRefreshTest {

    private static final long APPLICANT_ID = 1L;
    private static final long APPLICANT_DEPT_ID = 2L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private AiReviewService aiReviewService;

    @Autowired
    private AiReviewMapper aiReviewMapper;

    @Autowired
    private ExpenseReportMapper reportMapper;

    @Test
    void refresh_recomputes_same_version_and_keeps_single_row() {
        ReportDetailVO draft = createDraft();
        reportService.submitReport(draft.getId(), "idem-ai-refresh", null);
        ExpenseReport report = reportMapper.selectById(draft.getId());

        // 第一次重算：无记录 → runAgent 落一条
        aiReviewService.refresh(draft.getId());
        List<AiReview> first = rows(draft.getId());
        assertEquals(1, first.size());
        assertEquals(report.getVersion(), first.get(0).getReportVersion().intValue());

        // 第二次重算：删旧记录后再跑，仍只有一条（不会因幂等被跳过而保留旧结果）
        AiAdviceVO advice = aiReviewService.refresh(draft.getId());
        List<AiReview> second = rows(draft.getId());
        assertEquals(1, second.size());
        assertEquals(report.getVersion(), second.get(0).getReportVersion().intValue());
        // 建议必须带具体理由，不能只剩一个风险等级（mock 的 evidence 必须能在白名单里走通）
        assertNotNull(advice);
        assertNotNull(advice.getReasons());
        assertTrue(advice.getReasons().contains("预算"), "reasons 应包含具体建议，实际：" + advice.getReasons());
        // 建议不写业务表：单据状态仍是 PENDING
        assertEquals("PENDING", reportMapper.selectById(draft.getId()).getStatus());
    }

    private List<AiReview> rows(Long reportId) {
        return aiReviewMapper.selectList(new LambdaQueryWrapper<AiReview>()
                .eq(AiReview::getReportId, reportId)
                .orderByAsc(AiReview::getId));
    }

    private ReportDetailVO createDraft() {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 10, 18));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("北京-上海 高铁往返");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setApplicantId(APPLICANT_ID);
        dto.setApplicantDeptId(APPLICANT_DEPT_ID);
        dto.setTitle("AI 重算测试");
        dto.setExpenseType("TRAVEL");
        dto.setRemark("AiReviewRefreshTest");
        dto.setItems(List.of(item));
        return reportService.createDraft(dto);
    }
}
