package com.pulseflow.campaign.delay;

import com.pulseflow.campaign.delivery.DeliveryService;
import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.common.enums.CampaignStatus;
import com.pulseflow.common.enums.TriggerType;
import com.pulseflow.common.util.DedupKeyUtil;
import com.pulseflow.common.util.DelayedTaskConstants;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.profile.service.UserPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls the delayed-task ZSET every second, claims due tasks atomically
 * (pending → processing via Lua in {@link DelayedTaskManager}), checks the
 * business condition against MySQL/Redis, and either creates the delivery
 * task or cancels the delayed task.
 *
 * <p>This is the "独立线程每秒轮询" component required by design §3.2 that was
 * previously missing — {@link DelayedTaskManager} had the data-structure
 * primitives but no caller wired the poll → check → deliver loop.</p>
 *
 * <p>Condition for cart-abandon (the MVP delayed scenario): the cart item must
 * still be present in {@code user:cart:{userId}} (REMOVE_CART / ORDER_PAID both
 * HDEL it, so a paid/removed item naturally fails the condition).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayedTaskExecutor {

    private final DelayedTaskManager delayedTaskManager;
    private final RedissonClient redissonClient;
    private final CampaignMapper campaignMapper;
    private final DeliveryService deliveryService;
    private final UserPreferenceService userPreferenceService;

    private static final String DELAYED_TASK_TYPE = DelayedTaskConstants.CAMPAIGN_TASK_TYPE;
    private static final int BATCH_SIZE = 50;

    /**
     * Claim and execute due delayed tasks every second.
     */
    @Scheduled(fixedDelay = 1000)
    public void executeDue() {
        List<String> claimed;
        try {
            claimed = delayedTaskManager.claimTasks(
                    DELAYED_TASK_TYPE, System.currentTimeMillis(), BATCH_SIZE);
        } catch (Exception e) {
            log.error("Delayed task claim failed: {}", e.getMessage(), e);
            return;
        }
        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        for (String taskId : claimed) {
            try {
                processOne(taskId);
            } catch (Exception e) {
                // Keep the task in processing ZSET; DelayTaskRecoveryJob will
                // return it to pending after the timeout window.
                log.error("Delayed task processing failed: taskId={}, err={}", taskId, e.getMessage(), e);
            }
        }
    }

    /**
     * Process a single claimed delayed task.
     * taskId format = {campaignId}:{userId}:{cartItemId}:{addCartEventId}
     * (identical to {@link DedupKeyUtil#forDelayed}).
     */
    private void processOne(String taskId) {
        String[] parts = taskId.split(":");
        if (parts.length < 4) {
            log.warn("Malformed delayed taskId, cancelling: {}", taskId);
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId);
            return;
        }

        Long campaignId = Long.valueOf(parts[0]);
        Long userId = Long.valueOf(parts[1]);
        String cartItemId = parts[2];
        String addCartEventId = parts[3];

        Campaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null || !CampaignStatus.ACTIVE.name().equals(campaign.getStatus())
                || !TriggerType.DELAYED.name().equals(campaign.getTriggerType())) {
            // Campaign no longer eligible — business cancel, safe to remove.
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId);
            log.info("Delayed task cancelled (campaign inactive): taskId={}", taskId);
            return;
        }

        // Condition check: cart item must still be present.
        boolean stillInCart = isCartItemPresent(userId, cartItemId);
        if (!stillInCart) {
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId);
            log.info("Delayed task cancelled (item no longer in cart): taskId={}", taskId);
            return;
        }

        // Quick pre-filter: DND / unsubscribed / already converted.
        if (!userPreferenceService.canDeliver(userId, campaignId, campaign.getChannel())) {
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId);
            log.info("Delayed task cancelled (pre-filter): taskId={}", taskId);
            return;
        }

        // Condition met — create the delivery task. dedup_key == taskId, so
        // delivery_task.uk_dedup prevents any duplicate delivery.
        DeliveryTaskDto dto = DeliveryTaskDto.builder()
                .campaignId(campaignId)
                .userId(userId)
                .dedupKey(taskId)
                .triggerEventId(addCartEventId)
                .channel(campaign.getChannel())
                .messageContent(campaign.getMessageTemplate())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            deliveryService.createDeliveryTask(dto);
            // 只有真正成功或命中 dedup_key（之前已创建）才删除 processing 任务。
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId);
            log.info("Delayed delivery created: campaign={}, userId={}, cartItemId={}",
                    campaignId, userId, cartItemId);
        } catch (DuplicateKeyException e) {
            // 命中 uk_dedup：之前已成功创建过触达任务，安全删除。
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId);
            log.info("Delayed task already delivered (dedup hit): taskId={}", taskId);
        }
        // 其它异常（DB 临时故障、Kafka 不可用等）不删 processing，
        // 交由 DelayTaskRecoveryJob 超时后放回 pending 重试。
        // 注意：createDeliveryTask 内部对 Kafka 投递失败已吞掉异常并保留 dispatch_status=PENDING，
        // 那种情况 task 已落库，可以视为成功创建，此处不会进 catch。
    }

    private boolean isCartItemPresent(Long userId, String cartItemId) {
        // user:cart:{userId} is a Redis HASH; field == cartItemId.
        // REMOVE_CART / ORDER_PAID both HDEL the field, so a paid/removed
        // item naturally fails this existence check.
        RMap<String, String> cart = redissonClient.getMap("user:cart:" + userId);
        return cart.containsKey(cartItemId);
    }
}
