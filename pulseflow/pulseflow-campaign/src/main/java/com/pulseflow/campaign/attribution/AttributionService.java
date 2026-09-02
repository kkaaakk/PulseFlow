package com.pulseflow.campaign.attribution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.common.enums.AttributionTaskStatus;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
    static final int ATTRIBUTION_RETRY_DELAY_SECONDS = 30;
    static final int ATTRIBUTION_RECONCILIATION_BATCH_SIZE = 100;
    private static final String ATTRIBUTION_DELAY_KEY = "delay:attribution";
    private static final String ATTRIBUTION_PROCESSING_KEY = "delay:attribution:processing";
    private static final int CLAIM_BATCH_SIZE = 100;

    public enum ExecutionResult {
        MATCHED,
        EXPIRED,
        ALREADY_TERMINAL
    }

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
     * Atomically return one claimed task to the pending queue for a short retry.
     * The membership check prevents a late/duplicate failure handler from
     * resurrecting a task that has already been completed by another path.
     *
     * <p>KEYS[1] = delay:attribution:processing
     * KEYS[2] = delay:attribution (pending)
     * ARGV[1] = target event id
     * ARGV[2] = retry timestamp (epoch millis)</p>
     */
    private static final String REQUEUE_ATTRIBUTION_LUA = """
            local processingKey = KEYS[1]
            local pendingKey = KEYS[2]
            local taskId = ARGV[1]
            local retryAt = tonumber(ARGV[2])

            if redis.call('ZSCORE', processingKey, taskId) == false then
                return 0
            end

            redis.call('ZREM', processingKey, taskId)
            redis.call('ZADD', pendingKey, retryAt, taskId)
            return 1
            """;

    /**
     * Recover a PENDING DB task only when neither Redis queue currently owns it.
     * The check and ZADD are one Redis operation so duplicate target-event
     * deliveries cannot race a consumer claim and create a second queue entry.
     *
     * <p>KEYS[1] = delay:attribution (pending)
     * KEYS[2] = delay:attribution:processing
     * ARGV[1] = target event id
     * ARGV[2] = execution timestamp (epoch millis)</p>
     */
    private static final String ENSURE_PENDING_ATTRIBUTION_LUA = """
            local pendingKey = KEYS[1]
            local processingKey = KEYS[2]
            local taskId = ARGV[1]
            local executeAt = tonumber(ARGV[2])

            if redis.call('ZSCORE', pendingKey, taskId) ~= false then
                return 0
            end
            if redis.call('ZSCORE', processingKey, taskId) ~= false then
                return 0
            end

            redis.call('ZADD', pendingKey, executeAt, taskId)
            return 1
            """;

    /**
     * Called when a target event (like ORDER_PAID) arrives.
     * Creates the attribution waiting task. Redis scheduling is registered as
     * an after-commit action so a consumer can never claim a task that is not
     * visible in MySQL yet.
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
                    .status(AttributionTaskStatus.PENDING.name())
                    .graceUntil(graceUntil)
                    .build();

            attributionTaskMapper.insert(task);

            long executeAtMillis = toEpochMillis(graceUntil);
            schedulePendingTaskAfterCommit(targetEventId, executeAtMillis);

            log.info("Attribution task created: targetEventId={}, graceUntil={}",
                    targetEventId, graceUntil);
        } catch (DuplicateKeyException e) {
            recoverOrphanedPendingTask(targetEventId);
        }
    }

    /**
     * A duplicate target event may be the replay that follows a crash after the
     * MySQL insert but before the Redis ZADD. Only a still-PENDING DB row is
     * eligible for recovery; terminal rows must remain terminal.
     */
    private void recoverOrphanedPendingTask(String targetEventId) {
        AttributionTask existing = attributionTaskMapper.selectOne(
                new LambdaQueryWrapper<AttributionTask>()
                        .eq(AttributionTask::getTargetEventId, targetEventId));

        if (existing == null) {
            log.warn("Duplicate attribution task insert but existing row was not found: targetEventId={}",
                    targetEventId);
            return;
        }
        if (!AttributionTaskStatus.PENDING.name().equals(existing.getStatus())) {
            log.info("Attribution task already terminal for target event: targetEventId={}, status={}",
                    targetEventId, existing.getStatus());
            return;
        }

        long now = System.currentTimeMillis();
        long executeAtMillis = existing.getGraceUntil() == null
                ? now
                : Math.max(toEpochMillis(existing.getGraceUntil()), now);
        schedulePendingTaskAfterCommit(targetEventId, executeAtMillis);
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
     * Atomically move a failed claimed task from processing back to pending.
     * A short retry delay separates transient execution failures from the
     * attribution grace window, which is only for waiting on out-of-order clicks.
     */
    public void requeueClaimedTask(String targetEventId) {
        long retryAt = System.currentTimeMillis()
                + ATTRIBUTION_RETRY_DELAY_SECONDS * 1000L;
        Object result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                REQUEUE_ATTRIBUTION_LUA,
                RScript.ReturnType.INTEGER,
                List.of(ATTRIBUTION_PROCESSING_KEY, ATTRIBUTION_DELAY_KEY),
                targetEventId,
                retryAt);

        if (result instanceof Number && ((Number) result).longValue() == 1L) {
            log.info("Requeued failed attribution task: targetEventId={}, retryAt={}",
                    targetEventId, retryAt);
        } else {
            log.info("Attribution task was no longer in processing; skip requeue: targetEventId={}",
                    targetEventId);
        }
    }

    /**
     * Execute attribution matching for a target event after grace window expires.
     * This is called by AttributionTaskConsumer when the delay ZSET triggers.
     *
     * <p>A missing DB row is retryable. A terminal row is an idempotent
     * success, so the consumer may safely remove its processing claim.</p>
     */
    @Transactional
    public ExecutionResult executeAttribution(String targetEventId) {
        // Load the attribution task
        AttributionTask task = attributionTaskMapper.selectOne(
                new LambdaQueryWrapper<AttributionTask>()
                        .eq(AttributionTask::getTargetEventId, targetEventId));

        if (task == null) {
            throw new AttributionTaskNotFoundException(targetEventId);
        }
        if (AttributionTaskStatus.MATCHED.name().equals(task.getStatus())
                || AttributionTaskStatus.EXPIRED.name().equals(task.getStatus())) {
            log.info("Attribution task already terminal: targetEventId={}, status={}",
                    targetEventId, task.getStatus());
            return ExecutionResult.ALREADY_TERMINAL;
        }
        if (!AttributionTaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new IllegalStateException("Unexpected attribution task status: targetEventId="
                    + targetEventId + ", status=" + task.getStatus());
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
            markTaskExpired(task);
            log.info("No clicks found for attribution: targetEventId={}", targetEventId);
            return ExecutionResult.EXPIRED;
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

                    markTaskMatched(task, lastClick.getTaskId());

                    log.info("Attribution matched: targetEventId={}, campaignId={}, clickEventId={}",
                            targetEventId, delivery.getCampaignId(), lastClick.getId());
                    return ExecutionResult.MATCHED;
                } catch (DuplicateKeyException e) {
                    // The unique target-event key means the business operation
                    // already succeeded. Complete the DB state as well so the
                    // Consumer can safely remove the Redis claim without
                    // leaving a terminal record paired with a PENDING task.
                    markTaskMatched(task, lastClick.getTaskId());
                    log.info("Attribution already recorded; task marked MATCHED for targetEventId={}",
                            targetEventId);
                    return ExecutionResult.MATCHED;
                }
            }
        }

        // No valid attribution found
        markTaskExpired(task);
        log.info("No valid attribution match for targetEventId={}", targetEventId);
        return ExecutionResult.EXPIRED;
    }

    /**
     * Reconcile a bounded batch of MySQL PENDING tasks with Redis. MySQL is the
     * source of truth; the Lua membership check makes this safe to run while a
     * consumer is claiming a task and prevents pending/processing duplicates.
     *
     * @return number of tasks newly restored to the pending ZSET
     */
    public int reconcilePendingTasks() {
        List<AttributionTask> pendingTasks = attributionTaskMapper.selectList(
                new LambdaQueryWrapper<AttributionTask>()
                        .eq(AttributionTask::getStatus, AttributionTaskStatus.PENDING.name())
                        .orderByAsc(AttributionTask::getId)
                        .last("LIMIT " + ATTRIBUTION_RECONCILIATION_BATCH_SIZE));

        int recovered = 0;
        for (AttributionTask task : pendingTasks == null
                ? Collections.<AttributionTask>emptyList() : pendingTasks) {
            if (task == null || task.getTargetEventId() == null) {
                continue;
            }
            long executeAtMillis = task.getGraceUntil() == null
                    ? System.currentTimeMillis()
                    : Math.max(toEpochMillis(task.getGraceUntil()), System.currentTimeMillis());
            try {
                if (ensurePendingTask(task.getTargetEventId(), executeAtMillis)) {
                    recovered++;
                    log.warn("Reconciled PENDING attribution task into Redis: targetEventId={}, executeAt={}",
                            task.getTargetEventId(), executeAtMillis);
                }
            } catch (Exception e) {
                // Leave the DB row PENDING. A later reconciliation pass will
                // retry the Redis write; one broken task must not hide others.
                log.error("Failed to reconcile attribution task {}: {}",
                        task.getTargetEventId(), e.getMessage(), e);
            }
        }
        return recovered;
    }

    /**
     * Publish Redis scheduling only after the surrounding DB transaction has
     * committed. Direct calls without a transaction are safe because the
     * MyBatis statement has already auto-committed.
     */
    private void schedulePendingTaskAfterCommit(String targetEventId, long executeAtMillis) {
        Runnable publish = () -> {
            boolean scheduled = ensurePendingTask(targetEventId, executeAtMillis);
            if (scheduled) {
                log.info("Attribution task scheduled after DB commit: targetEventId={}, executeAt={}",
                        targetEventId, executeAtMillis);
            } else {
                log.info("Attribution task is already owned by a Redis queue: targetEventId={}",
                        targetEventId);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        publish.run();
                    } catch (Exception e) {
                        // The DB commit cannot be rolled back at this point.
                        // Reconciliation will restore this PENDING task.
                        log.error("Failed to schedule attribution task after DB commit: targetEventId={}, {}",
                                targetEventId, e.getMessage(), e);
                    }
                }
            });
            return;
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Attribution scheduling requires active transaction synchronization");
        }

        // Unit callers and non-Spring integrations have no transaction
        // synchronization; the INSERT above has already committed.
        publish.run();
    }

    private boolean ensurePendingTask(String targetEventId, long executeAtMillis) {
        Object result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                ENSURE_PENDING_ATTRIBUTION_LUA,
                RScript.ReturnType.INTEGER,
                List.of(ATTRIBUTION_DELAY_KEY, ATTRIBUTION_PROCESSING_KEY),
                targetEventId,
                executeAtMillis);
        return result instanceof Number && ((Number) result).longValue() == 1L;
    }

    private void markTaskMatched(AttributionTask task, Long matchedTaskId) {
        int updated = attributionTaskMapper.update(null,
                new LambdaUpdateWrapper<AttributionTask>()
                        .eq(AttributionTask::getId, task.getId())
                        .eq(AttributionTask::getStatus, AttributionTaskStatus.PENDING.name())
                        .set(AttributionTask::getStatus, AttributionTaskStatus.MATCHED.name())
                        .set(AttributionTask::getMatchedTaskId, matchedTaskId));
        requireTaskUpdate(task, updated, AttributionTaskStatus.MATCHED);
    }

    private void markTaskExpired(AttributionTask task) {
        int updated = attributionTaskMapper.update(null,
                new LambdaUpdateWrapper<AttributionTask>()
                        .eq(AttributionTask::getId, task.getId())
                        .eq(AttributionTask::getStatus, AttributionTaskStatus.PENDING.name())
                        .set(AttributionTask::getStatus, AttributionTaskStatus.EXPIRED.name()));
        requireTaskUpdate(task, updated, AttributionTaskStatus.EXPIRED);
    }

    private void requireTaskUpdate(AttributionTask task, int updated, AttributionTaskStatus expectedStatus) {
        if (updated != 1) {
            throw new IllegalStateException("Attribution task status was not updated: targetEventId="
                    + task.getTargetEventId() + ", expectedStatus=" + expectedStatus);
        }
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
