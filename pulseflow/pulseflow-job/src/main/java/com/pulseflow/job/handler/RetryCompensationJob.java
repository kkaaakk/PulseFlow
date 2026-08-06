package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.common.enums.CompensationStatus;
import com.pulseflow.common.enums.TaskStatus;
import com.pulseflow.entity.DataCompensationTask;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.DataCompensationTaskMapper;
import com.pulseflow.mapper.DeliveryTaskMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryCompensationJob {

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DataCompensationTaskMapper compensationTaskMapper;

    @XxlJob("retryCompensationJob")
    public void execute() {
        log.info("RetryCompensationJob started");

        LocalDateTime now = LocalDateTime.now();

        // Reset WAIT_RETRY tasks to PENDING for re-dispatch
        deliveryTaskMapper.update(null,
                new LambdaUpdateWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getStatus, TaskStatus.WAIT_RETRY.name())
                        .le(DeliveryTask::getNextRetryAt, now)
                        .lt(DeliveryTask::getRetryCount, 5)
                        .set(DeliveryTask::getStatus, TaskStatus.PENDING.name())
                        .set(DeliveryTask::getDispatchStatus, "PENDING")
                        .set(DeliveryTask::getPublishedAt, null)
                        .set(DeliveryTask::getProcessingAt, null)
                        .set(DeliveryTask::getNextRetryAt, null));

        // Recover PROCESSING timeout tasks (stuck after consumer crash) → WAIT_RETRY
        LocalDateTime fiveMinutesAgo = now.minusMinutes(5);
        deliveryTaskMapper.update(null,
                new LambdaUpdateWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getStatus, TaskStatus.PROCESSING.name())
                        .lt(DeliveryTask::getProcessingAt, fiveMinutesAgo)
                        .lt(DeliveryTask::getRetryCount, 5)
                        .set(DeliveryTask::getStatus, TaskStatus.WAIT_RETRY.name())
                        .setSql("retry_count = retry_count + 1"));

        // Mark over-retry tasks as FAILED
        deliveryTaskMapper.update(null,
                new LambdaUpdateWrapper<DeliveryTask>()
                        .in(DeliveryTask::getStatus,
                                TaskStatus.PROCESSING.name(),
                                TaskStatus.WAIT_RETRY.name())
                        .ge(DeliveryTask::getRetryCount, 5)
                        .set(DeliveryTask::getStatus, TaskStatus.FAILED.name()));

        // Mark over-retry compensation tasks as FAILED
        compensationTaskMapper.update(null,
                new LambdaUpdateWrapper<DataCompensationTask>()
                        .eq(DataCompensationTask::getStatus, CompensationStatus.PROCESSING.name())
                        .ge(DataCompensationTask::getRetryCount, 5)
                        .set(DataCompensationTask::getStatus, CompensationStatus.FAILED.name()));

        log.info("RetryCompensationJob completed");
    }
}
