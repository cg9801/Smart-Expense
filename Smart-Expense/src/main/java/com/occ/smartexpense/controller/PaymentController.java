package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.service.PaymentService;
import com.occ.smartexpense.vo.PaymentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 付款单接口，见 docs/03-接口清单.md §2.4。
 * 现阶段只认 X-User-Id（临时身份），第 5 周接 JWT 后再补 FINANCE 角色校验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public Result<PageResult<PaymentVO>> list(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long payeeUserId,
            @RequestParam(required = false) String reportNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(paymentService.list(status, payeeUserId, reportNo, page, size));
    }

    @GetMapping("/{id}")
    public Result<PaymentVO> detail(@PathVariable("id") Long id) {
        return Result.ok(paymentService.detail(id));
    }

    /** 模拟银行回执，触发 T6 */
    @PostMapping("/{id}/confirm")
    public Result<PaymentVO> confirm(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(paymentService.confirm(id, userId));
    }
}
