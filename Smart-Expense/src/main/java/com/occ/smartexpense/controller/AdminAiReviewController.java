package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.security.RequireRole;
import com.occ.smartexpense.service.AiReviewService;
import com.occ.smartexpense.vo.AiAdviceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 建议排障接口（docs/03 §2.7）。
 * 只做"重算"这一件事：AI 仍然只写 ai_review，不参与审批决策。
 */
@RestController
@RequestMapping("/api/v1/admin/ai-review")
@RequiredArgsConstructor
@RequireRole({"ADMIN"})
public class AdminAiReviewController {

    private final AiReviewService aiReviewService;

    /** 手动重算某单当前版本的建议：先删旧记录再跑，绕过同版本幂等 */
    @PostMapping("/refresh")
    public Result<AiAdviceVO> refresh(@RequestParam Long reportId) {
        return Result.ok(aiReviewService.refresh(reportId));
    }
}
