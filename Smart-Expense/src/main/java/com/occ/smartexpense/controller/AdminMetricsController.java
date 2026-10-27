package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.service.MetricsService;
import com.occ.smartexpense.vo.MetricsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 运营指标接口，契约见 docs/10-指标与监控.md §2。
 * 只读接口；JWT 接入后再限制 ADMIN/FINANCE。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/metrics")
public class AdminMetricsController {

    private final MetricsService metricsService;

    @GetMapping
    public Result<MetricsVO> overview(
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 X-User-Id");
        }
        return Result.ok(metricsService.overview());
    }
}
