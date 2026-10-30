package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.security.RequireRole;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.PaymentService;
import com.occ.smartexpense.vo.PaymentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 付款单接口，见 docs/03 §2.4 与 docs/12 §8。
 * 财务动作，要求 FINANCE / ADMIN 角色。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
@RequireRole({"FINANCE", "ADMIN"})
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public Result<PageResult<PaymentVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long payeeUserId,
            @RequestParam(required = false) String reportNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(paymentService.list(status, payeeUserId, reportNo, page, size));
    }

    @GetMapping("/{id}")
    public Result<PaymentVO> detail(@PathVariable("id") Long id) {
        return Result.ok(paymentService.detail(id));
    }

    /** 模拟银行回执，触发 T6 */
    @PostMapping("/{id}/confirm")
    public Result<PaymentVO> confirm(@PathVariable("id") Long id) {
        Long userId = UserContext.requireUserId();
        return Result.ok(paymentService.confirm(id, userId));
    }
}
