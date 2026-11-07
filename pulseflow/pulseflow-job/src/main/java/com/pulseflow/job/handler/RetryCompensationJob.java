package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.common.enums.CompensationStatus;
import com.pulseflow.entity.DataCompensationTask;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.campaign.delivery.DeliveryRecoveryService;
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
    private final DeliveryRecoveryService recovery;

    /**
     * 重置到期触达重试任务并恢复超时处理任务；对达到五次上限的触达或处理中补偿任务标记 FAILED。
     */
    @XxlJob("retryCompensationJob")
    public void execute() {
        log.info("RetryCompensationJob started");

        LocalDateTime now = LocalDateTime.now();

        // Recover channel receipts BEFORE retry exhaustion or quota. Bound each scheduler batch.
        var candidates = deliveryTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeliveryTask>()
                        .and(q -> q.eq(DeliveryTask::getStatus, "WAIT_RETRY")
                                .and(w -> w.le(DeliveryTask::getNextRetryAt, now).or().isNull(DeliveryTask::getNextRetryAt)
                                        .or().ge(DeliveryTask::getRetryCount, 5)
                                        .or().apply("EXISTS (SELECT 1 FROM in_app_message m WHERE m.business_key=delivery_task.id)")
                                        .or().apply("EXISTS (SELECT 1 FROM push_record p WHERE p.business_key=delivery_task.id)"))
                                .or(w -> w.eq(DeliveryTask::getStatus, "PROCESSING")
                                        .and(stale -> stale.lt(DeliveryTask::getProcessingAt, now.minusMinutes(5))
                                                .or().isNull(DeliveryTask::getProcessingAt))))
                        .orderByAsc(DeliveryTask::getUpdatedAt).orderByAsc(DeliveryTask::getId).last("LIMIT 100"));
        for (DeliveryTask task : candidates) {
            var condition = new LambdaUpdateWrapper<DeliveryTask>()
                    .eq(DeliveryTask::getId, task.getId()).eq(DeliveryTask::getStatus, task.getStatus())
                    .eq(DeliveryTask::getRetryCount, task.getRetryCount())
                    .isNull(DeliveryTask::getSentAt);
            if ("PROCESSING".equals(task.getStatus())) {
                if (task.getProcessingAt() == null) condition.isNull(DeliveryTask::getProcessingAt);
                else condition.eq(DeliveryTask::getProcessingAt, task.getProcessingAt());
            }
            try {
                if (recovery.recover(task)) continue;
            } catch (DeliveryRecoveryService.ReceiptConflictException conflict) {
                deliveryTaskMapper.update(null, condition.set(DeliveryTask::getStatus, "FAILED")
                        .set(DeliveryTask::getLastError, conflict.getMessage()).set(DeliveryTask::getNextRetryAt, null));
                continue;
            }
            if ("EMAIL".equals(task.getChannel()) && task.getFirstSendAttemptAt() != null) {
                deliveryTaskMapper.update(null, condition.set(DeliveryTask::getStatus, "FAILED")
                        .set(DeliveryTask::getNextRetryAt, null)
                        .set(DeliveryTask::getLastError, "EMAIL_SEND_OUTCOME_UNKNOWN"));
            } else if (task.getRetryCount() >= 5) {
                deliveryTaskMapper.update(null, condition.set(DeliveryTask::getStatus, "FAILED")
                        .set(DeliveryTask::getNextRetryAt, null));
            } else if ("PROCESSING".equals(task.getStatus())) {
                deliveryTaskMapper.update(null, condition.set(DeliveryTask::getStatus, "WAIT_RETRY")
                        // next_retry_at has legacy second precision: round down so recovery is due immediately.
                        .set(DeliveryTask::getNextRetryAt, now.withNano(0)).setSql("retry_count=retry_count+1"));
            } else if (task.getNextRetryAt() == null || !task.getNextRetryAt().isAfter(now)) {
                deliveryTaskMapper.update(null, condition.set(DeliveryTask::getStatus, "PENDING")
                        .set(DeliveryTask::getDispatchStatus, "PENDING").set(DeliveryTask::getPublishedAt, null)
                        .set(DeliveryTask::getProcessingAt, null).set(DeliveryTask::getNextRetryAt, null));
            }
        }

        // Mark over-retry compensation tasks as FAILED
        compensationTaskMapper.update(null,
                new LambdaUpdateWrapper<DataCompensationTask>()
                        .eq(DataCompensationTask::getStatus, CompensationStatus.PROCESSING.name())
                        .ge(DataCompensationTask::getRetryCount, 5)
                        .set(DataCompensationTask::getStatus, CompensationStatus.FAILED.name()));

        log.info("RetryCompensationJob completed");
    }
}
