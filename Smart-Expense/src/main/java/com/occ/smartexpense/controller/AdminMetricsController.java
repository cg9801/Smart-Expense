package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.security.RequireRole;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.MetricsService;
import com.occ.smartexpense.vo.MetricsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 运营指标接口，契约见 docs/10 §2。
 * 只读接口，要求 ADMIN 角色（docs/12 §8）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/metrics")
@RequireRole({"ADMIN"})
public class AdminMetricsController {

    private final MetricsService metricsService;

    @GetMapping
    public Result<MetricsVO> overview() {
        UserContext.requireUserId();   // 保证已登录，未登录由拦截器/上下文抛 40101
        return Result.ok(metricsService.overview());
    }
}
