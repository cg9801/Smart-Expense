package com.occ.smartexpense.job;

import com.occ.smartexpense.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每日对账：默认跑 T-1（docs/08 §4）。
 * 与 OutboxRelay 共用单线程调度器，单次对账耗时较长时可调大 spring.task.scheduling.pool.size。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private final ReconcileService reconcileService;

    @Scheduled(cron = "0 30 1 * * ?")
    public void runDaily() {
        LocalDate bizDate = LocalDate.now().minusDays(1);
        try {
            reconcileService.run(bizDate);
        } catch (Exception e) {
            // 定时任务异常不能外抛，否则调度线程会认为任务失败并跳过后续触发
            log.error("每日对账失败 bizDate={}", bizDate, e);
        }
    }
}
