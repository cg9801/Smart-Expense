package com.occ.smartexpense.service;

import com.occ.smartexpense.vo.MetricsVO;

/**
 * 运营指标聚合，口径见 docs/10-指标与监控.md。
 */
public interface MetricsService {

    MetricsVO overview();
}
