package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseflow.campaign.decision.DecisionEngine;
import com.pulseflow.campaign.profile.RealtimeProfileUpdateService;
import com.pulseflow.common.enums.CompensationStatus;
import com.pulseflow.entity.DataCompensationTask;
import com.pulseflow.job.service.CompensationClaimService;
import com.pulseflow.mapper.DataCompensationTaskMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

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

        // Step 2: Claim one due PENDING task (atomic, multi-instance safe).
        DataCompensationTask task = compensationClaimService.claimOne();
        if (task == null) {
            return;
        }

        // Step 3: Process OUTSIDE transaction — Redis + decision are slow / external.
        try {
            Map<String, Object> eventMap = parseEvent(task.getPayload());

            // Redis 重放失败：直接走 handleFailure，不能 markDone。
            // 委托 RealtimeProfileUpdateService，与 EventConsumer Phase 2 共用同一份 Lua。
            boolean redisOk = realtimeProfileUpdateService.update(eventMap);
            if (!redisOk) {
                throw new IllegalStateException(
                        "Redis replay failed for event: " + task.getEventId());
            }

            // 决策重试：基础设施异常会从 evaluate 向外抛（DecisionEngine 已修复为不吞异常）。
            decisionEngine.evaluate(eventMap);

            markDone(task.getId());
            log.info("Compensation task {} completed", task.getEventId());
        } catch (Exception e) {
            log.error("Compensation task {} failed: {}", task.getEventId(), e.getMessage(), e);
            handleFailure(task, e);
        }
    }

    private void markDone(Long taskId) {
        compensationTaskMapper.update(null,
                new LambdaUpdateWrapper<DataCompensationTask>()
                        .eq(DataCompensationTask::getId, taskId)
                        .set(DataCompensationTask::getStatus, CompensationStatus.DONE.name())
                        .set(DataCompensationTask::getLockedAt, null));
    }

    private void handleFailure(DataCompensationTask task, Exception e) {
        int newRetryCount = task.getRetryCount() + 1;
        String errMsg = e.getMessage() != null
                ? e.getMessage().substring(0, Math.min(512, e.getMessage().length()))
                : "Unknown error";

        if (newRetryCount >= task.getMaxRetry()) {
            compensationTaskMapper.update(null,
                    new LambdaUpdateWrapper<DataCompensationTask>()
                            .eq(DataCompensationTask::getId, task.getId())
                            .set(DataCompensationTask::getStatus, CompensationStatus.FAILED.name())
                            .set(DataCompensationTask::getLastError, errMsg)
                            .set(DataCompensationTask::getLockedAt, null));
        } else {
            long backoffSeconds = (long) Math.pow(2, newRetryCount) * 30;
            compensationTaskMapper.update(null,
                    new LambdaUpdateWrapper<DataCompensationTask>()
                            .eq(DataCompensationTask::getId, task.getId())
                            .set(DataCompensationTask::getStatus, CompensationStatus.PENDING.name())
                            .set(DataCompensationTask::getRetryCount, newRetryCount)
                            .set(DataCompensationTask::getNextRetryAt,
                                    LocalDateTime.now().plusSeconds(backoffSeconds))
                            .set(DataCompensationTask::getLastError, errMsg)
                            .set(DataCompensationTask::getLockedAt, null));
        }
    }

    private Map<String, Object> parseEvent(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse compensation event payload", e);
        }
    }
}
