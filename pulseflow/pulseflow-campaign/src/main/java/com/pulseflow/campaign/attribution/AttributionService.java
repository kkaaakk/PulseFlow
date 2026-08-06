package com.pulseflow.campaign.attribution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.entity.AttributionRecord;
import com.pulseflow.entity.AttributionTask;
import com.pulseflow.entity.ClickEvent;
import com.pulseflow.entity.DeliveryRecord;
import com.pulseflow.mapper.AttributionRecordMapper;
import com.pulseflow.mapper.AttributionTaskMapper;
import com.pulseflow.mapper.ClickEventMapper;
import com.pulseflow.mapper.DeliveryRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttributionService {

    private final AttributionTaskMapper attributionTaskMapper;
    private final AttributionRecordMapper attributionRecordMapper;
    private final ClickEventMapper clickEventMapper;
    private final DeliveryRecordMapper deliveryRecordMapper;
    private final RedissonClient redissonClient;

    private static final int GRACE_WINDOW_SECONDS = 300; // 5 minutes
    private static final int ATTRIBUTION_WINDOW_HOURS = 24;
    private static final String ATTRIBUTION_DELAY_KEY = "delay:attribution";
    private static final String ATTRIBUTION_PROCESSING_KEY = "delay:attribution:processing";
    private static final int CLAIM_BATCH_SIZE = 100;

    /**
     * Lua 脚本：原子领取到期归因任务（pending → processing）。
     *
     * <p>之前 {@code pollExpiredTasks} 只用 {@code valueRange} 读取到期任务，
     * 没有原子领取，多实例会重复处理同一批任务（最终靠 attribution_record
     * 的 uk_target_event_id 兜底，但浪费资源且日志噪音大）。
     * 此脚本把到期任务从 pending ZSET 移到 processing ZSET，与
     * {@code DelayedTaskManager.claimTasks} 模式一致。</p>
     *
     * <p>KEYS[1] = delay:attribution (pending)
     * KEYS[2] = delay:attribution:processing
     * ARGV[1] = now (epoch millis)
     * ARGV[2] = batch size</p>
     */
    private static final String CLAIM_ATTRIBUTION_LUA = """
            local pendingKey = KEYS[1]
            local processingKey = KEYS[2]
            local now = tonumber(ARGV[1])
            local batchSize = tonumber(ARGV[2])

            local tasks = redis.call('ZRANGEBYSCORE', pendingKey, 0, now, 'LIMIT', 0, batchSize)
            if #tasks == 0 then
                return {}
            end

            for i, taskId in ipairs(tasks) do
                redis.call('ZREM', pendingKey, taskId)
                redis.call('ZADD', processingKey, now, taskId)
            end

            return tasks
            """;

    /**
     * Called when a target event (like ORDER_PAID) arrives.
     * Creates attribution waiting task and schedules delayed matching.
     */
    @Transactional
    public void onTargetEvent(String targetEventId, Long userId, String targetEventType,
                               LocalDateTime targetEventTime) {
        LocalDateTime graceUntil = targetEventTime.plusSeconds(GRACE_WINDOW_SECONDS);

        try {
            AttributionTask task = AttributionTask.builder()
                    .targetEventId(targetEventId)
                    .userId(userId)
                    .targetEventType(targetEventType)
                    .targetEventTime(targetEventTime)
                    .status("PENDING")
                    .graceUntil(graceUntil)
                    .build();

            attributionTaskMapper.insert(task);

            // Add to Redis delay ZSET
            long executeAtMillis = graceUntil.atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            redissonClient.getScoredSortedSet(ATTRIBUTION_DELAY_KEY)
                    .add(executeAtMillis, targetEventId);

            log.info("Attribution task created: targetEventId={}, graceUntil={}",
                    targetEventId, graceUntil);
        } catch (DuplicateKeyException e) {
            log.info("Attribution task already exists for target event: {}", targetEventId);
        }
    }

    /**
     * Atomically claim due attribution tasks (pending → processing).
     *
     * <p>替代原来的 {@code pollExpiredTasks}：用 Lua 原子领取，多实例不会重复处理。
     * 处理完成后必须调用 {@link #completeClaimedTask} 从 processing ZSET 移除。</p>
     *
     * @return 本次领取到的 targetEventId 集合（已原子移入 processing）
     */
    public Set<String> claimExpiredTasks() {
        long now = System.currentTimeMillis();
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(RScript.Mode.READ_WRITE,
                CLAIM_ATTRIBUTION_LUA,
                RScript.ReturnType.MULTI,
                List.of(ATTRIBUTION_DELAY_KEY, ATTRIBUTION_PROCESSING_KEY),
                now, CLAIM_BATCH_SIZE);

        if (result == null || result.isEmpty()) {
            return Collections.emptySet();
        }
        return result.stream().map(Object::toString).collect(Collectors.toSet());
    }

    /**
     * 处理完成后从 processing ZSET 移除。
     */
    public void completeClaimedTask(String targetEventId) {
        redissonClient.getScoredSortedSet(ATTRIBUTION_PROCESSING_KEY).remove(targetEventId);
    }

    /**
     * Execute attribution matching for a target event after grace window expires.
     * This is called by AttributionTaskConsumer when the delay ZSET triggers.
     */
    @Transactional
    public void executeAttribution(String targetEventId) {
        // Load the attribution task
        AttributionTask task = attributionTaskMapper.selectOne(
                new LambdaQueryWrapper<AttributionTask>()
                        .eq(AttributionTask::getTargetEventId, targetEventId)
                        .eq(AttributionTask::getStatus, "PENDING"));

        if (task == null) {
            log.info("No pending attribution task for targetEventId={}", targetEventId);
            return;
        }

        // Query click events in attribution window (24h before target event)
        LocalDateTime windowStart = task.getTargetEventTime().minusHours(ATTRIBUTION_WINDOW_HOURS);
        List<ClickEvent> clicks = clickEventMapper.selectList(
                new LambdaQueryWrapper<ClickEvent>()
                        .eq(ClickEvent::getUserId, task.getUserId())
                        .ge(ClickEvent::getClickTime, windowStart)
                        .lt(ClickEvent::getClickTime, task.getTargetEventTime())
                        .orderByDesc(ClickEvent::getClickTime));

        if (clicks.isEmpty()) {
            attributionTaskMapper.update(null,
                    new LambdaUpdateWrapper<AttributionTask>()
                            .eq(AttributionTask::getId, task.getId())
                            .set(AttributionTask::getStatus, "EXPIRED"));
            log.info("No clicks found for attribution: targetEventId={}", targetEventId);
            return;
        }

        // Last-touch: pick the most recent click
        ClickEvent lastClick = clicks.get(0);

        // Validate: click must be after the delivery sent time
        if (lastClick.getTaskId() != null) {
            DeliveryRecord delivery = deliveryRecordMapper.selectOne(
                    new LambdaQueryWrapper<DeliveryRecord>()
                            .eq(DeliveryRecord::getTaskId, lastClick.getTaskId()));

            if (delivery != null && delivery.getSentAt() != null
                    && lastClick.getClickTime().isAfter(delivery.getSentAt())) {
                try {
                    AttributionRecord record = AttributionRecord.builder()
                            .clickEventId(lastClick.getId())
                            .targetEventId(targetEventId)
                            .userId(task.getUserId())
                            .campaignId(delivery.getCampaignId())
                            .taskId(lastClick.getTaskId())
                            .attributionModel("CLICK_LAST_TOUCH")
                            .attributionWindowHours(ATTRIBUTION_WINDOW_HOURS)
                            .creditedAt(LocalDateTime.now())
                            .build();

                    attributionRecordMapper.insert(record);

                    attributionTaskMapper.update(null,
                            new LambdaUpdateWrapper<AttributionTask>()
                                    .eq(AttributionTask::getId, task.getId())
                                    .set(AttributionTask::getStatus, "MATCHED")
                                    .set(AttributionTask::getMatchedTaskId, lastClick.getTaskId()));

                    log.info("Attribution matched: targetEventId={}, campaignId={}, clickEventId={}",
                            targetEventId, delivery.getCampaignId(), lastClick.getId());
                } catch (DuplicateKeyException e) {
                    log.info("Attribution already recorded for targetEventId={}", targetEventId);
                }
                return;
            }
        }

        // No valid attribution found
        attributionTaskMapper.update(null,
                new LambdaUpdateWrapper<AttributionTask>()
                        .eq(AttributionTask::getId, task.getId())
                        .set(AttributionTask::getStatus, "EXPIRED"));
        log.info("No valid attribution match for targetEventId={}", targetEventId);
    }
}
