package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.ReportService;
import com.occ.smartexpense.vo.ReportDetailVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报销单接口：只做参数接收、校验、调用 Service、包装 Result。
 * 接口契约见 docs/03-接口清单.md §2.2。
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping
    public Result<ReportDetailVO> createDraft(@Valid @RequestBody ReportCreateDTO dto) {
        return Result.ok(reportService.createDraft(dto));
    }

    @GetMapping("/{id}")
    public Result<ReportDetailVO> getDetail(@PathVariable Long id) {
        // 详情含金额、明细与审批链，必须登录后才能看（docs/12 §7）
        UserContext.requireUserId();
        return Result.ok(reportService.getDetail(id));
    }

    /**
     * 提交报销单，接口契约见 docs/03-接口清单.md §2.2。
     * Idempotency-Key 缺失时由 Service 抛 PARAM_ERROR（不依赖前端校验）。
     */
    @PostMapping("/{id}/submit")
    public Result<ReportDetailVO> submitReport(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return Result.ok(reportService.submitReport(id, idempotencyKey, traceId));
    }

    /** 撤回报销单：仅 PENDING 且无人审批时允许，见 docs/02-状态机.md T2。 */
    @PostMapping("/{id}/withdraw")
    public Result<ReportDetailVO> withdrawReport(
            @PathVariable Long id,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return Result.ok(reportService.withdrawReport(id, traceId));
    }
}
