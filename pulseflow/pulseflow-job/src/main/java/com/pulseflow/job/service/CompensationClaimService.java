package com.pulseflow.job.service;

import com.pulseflow.entity.DataCompensationTask;
import com.pulseflow.mapper.DataCompensationTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims a single PENDING compensation task inside its own short transaction.
 *
 * <p>Extracted from {@code CompensationJob} because the previous private
 * {@code claimTask()} was called via {@code this.} (self-invocation) — Spring's
 * proxy-based {@code @Transactional} silently no-op'd, so {@code FOR UPDATE}
 * never held a lock and multi-instance executors could grab the same row.</p>
 *
 * <p>Uses {@code FOR UPDATE SKIP LOCKED} so concurrent executors proceed
 * independently instead of blocking (MySQL 8+).</p>
 *
 * <p>过滤 {@code retry_count < max_retry}：达到重试上限的 PENDING 任务不再被领取，
 * 由 CompensationJob 的 stuck-recovery 转为 FAILED。MyBatis-Plus 的 LambdaQueryWrapper
 * 不支持列间比较，所以用自定义 SQL（见 {@link DataCompensationTaskMapper#selectOneDueUnderRetryForUpdate}）。</p>
 */
@Service
@RequiredArgsConstructor
public class CompensationClaimService {

    private final DataCompensationTaskMapper compensationTaskMapper;

    /**
     * Atomically claim the oldest due PENDING task (under retry limit).
     *
     * @return the claimed task (status now PROCESSING), or {@code null} if none due.
     */
    @Transactional
    public DataCompensationTask claimOne() {
        DataCompensationTask task = compensationTaskMapper.selectOneDueUnderRetryForUpdate();

        if (task == null) {
            return null;
        }

        compensationTaskMapper.markProcessing(task.getId());
        return task;
    }
}
