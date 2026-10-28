package com.pulseflow.campaign.attribution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 轮询到期归因任务，并协调执行、重排及孤立调度恢复。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttributionTaskConsumer {

    private final AttributionService attributionService;

    /**
     * 轮询并原子领取到期归因任务，成功清理领取记录，失败重新排队。
     *
     * Poll every 30 seconds for attribution tasks whose grace window has expired.
     *
     * <p>用 {@link AttributionService#claimExpiredTasks()} 原子领取（pending → processing），
     * 多实例不会重复处理。只有业务执行成功后才调用
     * {@link AttributionService#completeClaimedTask} 清理 processing ZSET；
     * 异常时调用 {@link AttributionService#requeueClaimedTask} 回到 pending，
     * 避免 PENDING 任务从调度系统中消失。</p>
     */
    @Scheduled(fixedDelay = 30000)
    public void processAttributionTasks() {
        Set<String> claimedTasks;
        try {
            claimedTasks = attributionService.claimExpiredTasks();
        } catch (Exception e) {
            log.error("Attribution claim failed: {}", e.getMessage(), e);
            return;
        }

        if (claimedTasks.isEmpty()) {
            return;
        }

        log.info("Processing {} expired attribution tasks", claimedTasks.size());

        for (String targetEventId : claimedTasks) {
            try {
                attributionService.executeAttribution(targetEventId);
            } catch (Exception e) {
                log.error("Attribution matching failed for {}: {}", targetEventId, e.getMessage(), e);
                try {
                    attributionService.requeueClaimedTask(targetEventId);
                } catch (Exception requeueException) {
                    log.error("Failed to requeue attribution task {} after execution failure: {}",
                            targetEventId, requeueException.getMessage(), requeueException);
                }
                continue;
            }

            try {
                attributionService.completeClaimedTask(targetEventId);
            } catch (Exception e) {
                // The business transaction completed, but cleanup failed. Try
                // the same atomic move used for execution failures so the
                // claimed item remains discoverable until cleanup succeeds.
                log.warn("Failed to remove completed attribution task {} from processing ZSET: {}",
                        targetEventId, e.getMessage());
                try {
                    attributionService.requeueClaimedTask(targetEventId);
                } catch (Exception requeueException) {
                    log.error("Failed to requeue attribution task {} after cleanup failure: {}",
                            targetEventId, requeueException.getMessage(), requeueException);
                }
            }
        }
    }

    /**
     * 从 MySQL 待处理任务重建 Redis 调度，修复提交后写缓存失败的窗口。
     *
     * Rebuild Redis scheduling from MySQL PENDING rows. This closes the
     * unavoidable DB-commit-to-Redis-write failure window without reviving
     * MATCHED/EXPIRED tasks (the service query only selects PENDING rows).
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void reconcileOrphanedTasks() {
        try {
            int recovered = attributionService.reconcilePendingTasks();
            if (recovered > 0) {
                log.info("Reconciled {} orphaned PENDING attribution tasks", recovered);
            }
        } catch (Exception e) {
            // Keep the next scheduled pass available; a transient DB/Redis
            // outage must not stop the consumer from processing owned claims.
            log.error("Attribution reconciliation failed: {}", e.getMessage(), e);
        }
    }
}
