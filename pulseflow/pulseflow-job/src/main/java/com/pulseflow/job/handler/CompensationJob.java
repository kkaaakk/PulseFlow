package com.pulseflow.job.handler;

import com.pulseflow.campaign.decision.DecisionEngine;
import com.pulseflow.campaign.attribution.ClickEventService;
import com.pulseflow.campaign.attribution.ClickDeliveryNotVisibleException;
import com.pulseflow.campaign.attribution.AttributionService;
import com.pulseflow.common.enums.CompensationTaskType;
import com.pulseflow.campaign.profile.RealtimeProfileUpdateService;
import com.pulseflow.common.enums.CompensationStatus;
import com.pulseflow.entity.DataCompensationTask;
import com.pulseflow.event.service.EventPersistenceService;
import com.pulseflow.job.service.CompensationClaimService;
import com.pulseflow.mapper.DataCompensationTaskMapper;
import com.pulseflow.mapper.UserEventMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;


/**
 * Replays EVENT_REPLAY compensation tasks: re-run the Redis Lua update and
 * re-evaluate the decision engine for events whose Phase 2/3 failed.
 *
 * <p>Flow (per execution):
 * <ol>
 *   <li>Recover stuck PROCESSING tasks (locked &gt; 5min, under retry limit) → PENDING.</li>
 *   <li>Claim ONE due PENDING task atomically via {@link CompensationClaimService}
 *       ({@code FOR UPDATE SKIP LOCKED}) — short transaction, no self-invocation.</li>
 *   <li>Process OUTSIDE any transaction: replay Redis + re-run decision.</li>
 *   <li>Update status (DONE / PENDING-with-backoff / FAILED) as single statements.</li>
 * </ol>
 *
 * <p>Processing happens outside the claim transaction so a slow Redis/decision
 * call does not hold a DB connection or row lock open.</p>
 *
 * <p><b>正确性约束</b>：Redis 重放或决策重试任一失败，必须走 handleFailure 退避重试，
 * 不能 markDone。否则补偿任务被标记成功，但实时画像 / 触达任务实际没恢复——
 * 直接破坏"Redis 故障补偿恢复"的核心承诺。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationJob {

    private final DataCompensationTaskMapper compensationTaskMapper;
    private final CompensationClaimService compensationClaimService;
    private final RealtimeProfileUpdateService realtimeProfileUpdateService;
    private final DecisionEngine decisionEngine;
    private final JdbcTemplate jdbcTemplate;
    private final ClickEventService clickEventService;
    private final AttributionService attributionService;

    private final EventPersistenceService events;
    private final UserEventMapper userEvents;
    private final TransactionTemplate transactionTemplate;

    /**
     * 恢复超时处理中补偿任务，再短事务抢占一条到期任务，在事务外重放业务；按结果标记完成或安排重试。
     */
    @XxlJob("compensationJob")
    public void execute() {
        log.info("CompensationJob started");

        // Step 1: Recover stuck PROCESSING tasks (locked > 5 min, under retry limit).
        // retry_count < max_retry is a column-to-column comparison → raw SQL apply().
        jdbcTemplate.update(
                "UPDATE data_compensation_task SET status = 'PENDING', " +
                        "retry_count = retry_count + 1, next_retry_at = NOW(), locked_at = NULL " +
                        "WHERE status = 'PROCESSING' AND locked_at < NOW() - INTERVAL 5 MINUTE " +
                        "AND retry_count < max_retry");
        // A crash on the final attempt must also leave a visible terminal row.
        jdbcTemplate.update("UPDATE data_compensation_task SET status = 'FAILED', locked_at = NULL "
                + "WHERE status = 'PROCESSING' AND locked_at < NOW() - INTERVAL 5 MINUTE "
                + "AND retry_count >= max_retry");
        jdbcTemplate.update("UPDATE data_compensation_task SET status = 'FAILED', locked_at = NULL "
                + "WHERE status = 'PENDING' AND retry_count >= max_retry");

        // Step 2: Claim one due PENDING task (atomic, multi-instance safe).
        DataCompensationTask task = compensationClaimService.claimOne();
        if (task == null) {
            return;
        }

        // Step 3: Process OUTSIDE transaction — Redis + decision are slow / external.
        try {
            if (CompensationTaskType.MARKETING_CLICK.name().equals(task.getTaskType())) {
                if (clickEventService.retry(task.getEventId()) == ClickEventService.Result.PENDING) {
                    throw new ClickDeliveryNotVisibleException();
                }
                markDone(task);
                return;
            }
            if (!CompensationTaskType.EVENT_REPLAY.name().equals(task.getTaskType())) {
                throw new IllegalStateException("unsupported_compensation_type");
            }
            var canonical = events.loadCanonical(task.getEventId());
            var event = canonical.getEvent();
            var eventMap = canonical.getContext();

            // Redis 重放失败：直接走 handleFailure，不能 markDone。
            // 委托 RealtimeProfileUpdateService，与 EventConsumer Phase 2 共用同一份 Lua。
            boolean redisOk = realtimeProfileUpdateService.update(eventMap);
            if (!redisOk) {
                throw new IllegalStateException(
                        "Redis replay failed for event: " + task.getEventId());
            }

            // 决策重试：基础设施异常会从 evaluate 向外抛（DecisionEngine 已修复为不吞异常）。
            decisionEngine.evaluate(eventMap);

            // Recover older EVENT_REPLAY target events too. New targets already
            // seed their durable task inside the event persistence transaction.
            if ("ORDER_PAID".equals(event.getEventType())) {
                attributionService.onTargetEvent(event.getEventId(), event.getUserId(),
                        event.getEventType(), event.getEffectiveEventTime());
            }

            markDone(task);
        } catch (Exception e) {
            log.error("Compensation failed: type={}, exception={}", task.getTaskType(), e.getClass().getSimpleName());
            handleFailure(task, e);
        }
    }

    private void markDone(DataCompensationTask task) {
        if (compensationTaskMapper.finishAttempt(task, CompensationStatus.DONE.name(), task.getRetryCount(),
                null, null) == 1) {
            log.info("Compensation task {} completed", task.getEventId());
        }
    }

    private void handleFailure(DataCompensationTask task, Exception failure) {
        int retryCount = task.getRetryCount() + 1;
        boolean exhausted = retryCount >= task.getMaxRetry();
        boolean notVisible = failure instanceof ClickDeliveryNotVisibleException;
        String error = notVisible ? "DELIVERY_NOT_VISIBLE" : "INFRASTRUCTURE_FAILURE";
        String status = exhausted ? CompensationStatus.FAILED.name() : CompensationStatus.PENDING.name();
        Long delay = exhausted ? null : 30L * (1L << Math.min(retryCount, 16));

        if (!exhausted || !notVisible) {
            compensationTaskMapper.finishAttempt(task, status, retryCount, error, delay);
            return;
        }
        // Keep the canonical click and its compensation terminal update atomic. The click
        // lock comes first, matching the event path's lock order.
        transactionTemplate.executeWithoutResult(transaction -> {
            userEvents.selectByEventIdForUpdate(task.getEventId());
            if (compensationTaskMapper.finishAttempt(task, status, retryCount, error, delay) == 1) {
                clickEventService.exhaustVisibilityRetries(task.getEventId());
            }
        });
    }
}
