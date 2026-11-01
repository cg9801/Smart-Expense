package com.occ.smartexpense.job;

import com.occ.smartexpense.service.ApprovalSlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批 SLA 定时任务（docs/02 §7）。
 * 只负责"什么时候扫"，扫描规则与事务边界在 ApprovalSlaServiceImpl。
 * 与 OutboxRelay / ReconcileJob 同一套调度风格：cron 可配、开关可关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalSlaJob {

    private final ApprovalSlaService approvalSlaService;

    @Value("${approval.sla.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${approval.sla.cron:0 */10 * * * *}")
    public void scanOverdue() {
        if (!enabled) {
            return;
        }
        try {
            int handled = approvalSlaService.scanOverdue();
            if (handled > 0) {
                log.info("审批 SLA 扫描完成，处理节点 {} 个", handled);
            }
        } catch (Exception e) {
            // 定时任务不能因为一次异常就停摆；下次扫描会重新捡起来
            log.warn("审批 SLA 扫描失败", e);
        }
    }
}
