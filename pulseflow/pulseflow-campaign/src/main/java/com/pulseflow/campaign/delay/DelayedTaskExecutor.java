package com.pulseflow.campaign.delay;

import com.pulseflow.campaign.delivery.DeliveryService;
import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.common.enums.CampaignStatus;
import com.pulseflow.common.enums.TriggerType;
import com.pulseflow.common.util.DedupKeyUtil;
import com.pulseflow.common.util.DelayedTaskConstants;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.profile.service.UserPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
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
    private final UserEventMapper userEventMapper;

    private static final String DELAYED_TASK_TYPE = DelayedTaskConstants.CAMPAIGN_TASK_TYPE;
    private static final int BATCH_SIZE = 50;

    /**
     * Claim and execute due delayed tasks every second.
     */
    @Scheduled(fixedDelay = 1000)
    public void executeDue() {
        long claimedAt = System.currentTimeMillis();
        List<String> claimed;
        try {
            claimed = delayedTaskManager.claimTasks(DELAYED_TASK_TYPE, claimedAt, BATCH_SIZE);
        } catch (Exception e) {
            log.error("Delayed task claim failed: {}", e.getMessage(), e);
            return;
        }
        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        for (String taskId : claimed) {
            try {
                processOne(taskId, claimedAt);
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
    private void processOne(String taskId, long claimedAt) {
        String[] parts = taskId.split(":", 4);
        boolean canonicalItem = parts.length == 4 && "cart".equals(parts[0]);
        if (parts.length != 4 || parts[3].isBlank() || taskId.codePointCount(0, taskId.length()) > 128) {
            completeMalformedTask(taskId, claimedAt);
            return;
        }

        Long campaignId;
        Long userId;
        try {
            campaignId = Long.valueOf(parts[canonicalItem ? 1 : 0]);
            userId = Long.valueOf(parts[canonicalItem ? 2 : 1]);
        } catch (NumberFormatException e) {
            completeMalformedTask(taskId, claimedAt);
            return;
        }
        String addCartEventId = parts[3];
        String cartItemId = canonicalItem ? userEventMapper.selectAddedCartItem(addCartEventId, userId) : parts[2];

        Campaign campaign = campaignMapper.selectById(campaignId);
        LocalDateTime now = LocalDateTime.now();
        if (campaign == null || !CampaignStatus.ACTIVE.name().equals(campaign.getStatus())
                || !TriggerType.DELAYED.name().equals(campaign.getTriggerType())
                || campaign.getStartTime() != null && now.isBefore(campaign.getStartTime())
                || campaign.getEndTime() != null && now.isAfter(campaign.getEndTime())) {
            // Campaign no longer eligible — business cancel, safe to remove.
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId, claimedAt);
            log.info("Delayed task cancelled (campaign inactive): taskId={}", taskId);
            return;
        }

        if (cartItemId == null || !isCartItemPresent(userId, cartItemId)) {
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId, claimedAt);
            log.info("Delayed task cancelled (item no longer in cart): taskId={}", taskId);
            return;
        }

        // Quick pre-filter: DND / unsubscribed / already converted.
        if (!userPreferenceService.canDeliver(userId, campaignId, campaign.getChannel())) {
            delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId, claimedAt);
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

        // The insert rechecks MySQL after Redis/preferences. A cancellation visible to this
        // statement prevents materialization, even when the cache still shows an item.
        deliveryService.createDelayedDeliveryTask(dto, cartItemId);
        delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId, claimedAt);
    }

    private void completeMalformedTask(String taskId, long claimedAt) {
        delayedTaskManager.completeTask(DELAYED_TASK_TYPE, taskId, claimedAt);
        log.warn("Discarded malformed delayed task key");
    }

    /**
     * 检查用户 Redis 购物车 HASH 是否仍含指定条目；移除或支付事件删除条目后延迟触达即不再满足条件。
     */
    private boolean isCartItemPresent(Long userId, String cartItemId) {
        // user:cart:{userId} is a Redis HASH; field == cartItemId.
        // REMOVE_CART / ORDER_PAID both HDEL the field, so a paid/removed
        // item naturally fails this existence check.
        RMap<String, String> cart = redissonClient.getMap("user:cart:" + userId, StringCodec.INSTANCE);
        return cart.containsKey(cartItemId);
    }
}
