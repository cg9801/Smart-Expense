package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.invoice.InvoiceRecognizerChain;
import com.occ.smartexpense.security.RequireRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI/识别配置排障接口（docs/18 §2.1）。
 * 只读，返回当前识别器链与环境变量是否注入，用于回答"为什么还是 mock"。
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiredArgsConstructor
@RequireRole({"ADMIN"})
public class AdminAiConfigController {

    private final InvoiceRecognizerChain invoiceRecognizerChain;

    @GetMapping("/providers")
    public Result<Map<String, Object>> providers() {
        return Result.ok(invoiceRecognizerChain.describe());
    }
}
