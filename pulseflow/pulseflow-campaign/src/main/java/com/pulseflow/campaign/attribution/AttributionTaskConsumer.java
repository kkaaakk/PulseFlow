package com.pulseflow.campaign.attribution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttributionTaskConsumer {

    private final AttributionService attributionService;

    /**
     * Poll every 30 seconds for attribution tasks whose grace window has expired.
     *
     * <p>用 {@link AttributionService#claimExpiredTasks()} 原子领取（pending → processing），
     * 多实例不会重复处理。处理完成（无论成功还是 EXPIRED）后必须调用
     * {@link AttributionService#completeClaimedTask} 清理 processing ZSET；
     * 异常时也清理，避免任务卡在 processing（虽然无超时恢复，但归因失败本身
     * 可接受——attribution_task 仍为 PENDING，下次 onTargetEvent 会重新入队）。</p>
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
            } finally {
                // 无论成功/失败/异常，都从 processing ZSET 移除。
                // 归因匹配是幂等的（attribution_record.uk_target_event_id 兜底），
                // 失败不重试，避免无限循环；attribution_task 状态由 executeAttribution 内部维护。
                try {
                    attributionService.completeClaimedTask(targetEventId);
                } catch (Exception e) {
                    log.warn("Failed to remove attribution task {} from processing ZSET: {}",
                            targetEventId, e.getMessage());
                }
            }
        }
    }
}
