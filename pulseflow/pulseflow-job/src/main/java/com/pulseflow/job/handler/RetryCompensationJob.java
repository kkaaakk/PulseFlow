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

/** 恢复到期或处理超时的触达任务，并将达到上限的触达与补偿任务标为失败。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryCompensationJob {

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DataCompensationTaskMapper compensationTaskMapper;

    /** 重置可重试触达的发送与发布状态，由消息补发任务继续投递；超限任务收敛为 FAILED。 */
    @XxlJob("retryCompensationJob")
    public void execute() {
        log.info("RetryCompensationJob started");

        LocalDateTime now = LocalDateTime.now();

        // 到期重试回到 PENDING，同时清除旧发布时间与领取时间，交给补发任务重新投递。
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

        // 消费者崩溃可能遗留 PROCESSING，超过五分钟后转入等待重试并增加次数。
        // Recover PROCESSING timeout tasks (stuck after consumer crash) → WAIT_RETRY
        LocalDateTime fiveMinutesAgo = now.minusMinutes(5);
        deliveryTaskMapper.update(null,
                new LambdaUpdateWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getStatus, TaskStatus.PROCESSING.name())
                        .lt(DeliveryTask::getProcessingAt, fiveMinutesAgo)
                        .lt(DeliveryTask::getRetryCount, 5)
                        .set(DeliveryTask::getStatus, TaskStatus.WAIT_RETRY.name())
                        .setSql("retry_count = retry_count + 1"));

        // 发送任务达到重试上限后停止恢复，明确记录失败终态。
        // Mark over-retry tasks as FAILED
        deliveryTaskMapper.update(null,
                new LambdaUpdateWrapper<DeliveryTask>()
                        .in(DeliveryTask::getStatus,
                                TaskStatus.PROCESSING.name(),
                                TaskStatus.WAIT_RETRY.name())
                        .ge(DeliveryTask::getRetryCount, 5)
                        .set(DeliveryTask::getStatus, TaskStatus.FAILED.name()));

        // 同时收敛已达到上限且仍卡在处理中的事件补偿任务。
        // Mark over-retry compensation tasks as FAILED
        compensationTaskMapper.update(null,
                new LambdaUpdateWrapper<DataCompensationTask>()
                        .eq(DataCompensationTask::getStatus, CompensationStatus.PROCESSING.name())
                        .ge(DataCompensationTask::getRetryCount, 5)
                        .set(DataCompensationTask::getStatus, CompensationStatus.FAILED.name()));

        log.info("RetryCompensationJob completed");
    }
}
