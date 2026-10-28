package com.pulseflow.campaign.delivery;

import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.common.enums.DispatchStatus;
import com.pulseflow.common.enums.TaskStatus;
import com.pulseflow.common.exception.PulseFlowException;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.DeliveryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/** 持久化幂等触达任务并发布 Kafka，供恢复任务补发。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private static final String DELIVERY_TOPIC = "pulseflow.delivery";

    /**
     * 先以 dedup_key 唯一约束落库，再发布 Kafka；失败任务可由扫描恢复投递。
     *
     * Create delivery task with dedup protection.
     * Step 1: INSERT delivery_task (UK on dedup_key)
     * Step 2: Publish to Kafka (outbox pattern)
     */
    @Transactional
    public void createDeliveryTask(DeliveryTaskDto dto) {
        try {
            DeliveryTask task = DeliveryTask.builder()
                    .campaignId(dto.getCampaignId())
                    .userId(dto.getUserId())
                    .dedupKey(dto.getDedupKey())
                    .triggerEventId(dto.getTriggerEventId())
                    .channel(dto.getChannel())
                    .status(TaskStatus.PENDING.name())
                    .dispatchStatus(DispatchStatus.PENDING.name())
                    .messageContent(dto.getMessageContent())
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            deliveryTaskMapper.insert(task);
            log.info("Delivery task created: dedupKey={}, taskId={}", dto.getDedupKey(), task.getId());

            // 数据库任务保留发布状态，消息发送失败时由 DispatchRetryJob 扫描补发。
            // Outbox: publish to Kafka
            publishToKafka(task);

        } catch (DuplicateKeyException e) {
            // 相同业务幂等键表示触达已被安排，直接跳过，不能再生成第二个任务。
            log.info("Duplicate delivery task skipped: dedupKey={}", dto.getDedupKey());
        }
    }

    /**
     * 发送触达任务消息，成功后更新 dispatch_status，失败保留后续补发能力。
     *
     * Publish delivery task to Kafka.
     * On success: UPDATE dispatch_status = 'PUBLISHED'
     */
    private void publishToKafka(DeliveryTask task) {
        try {
            String payload = JsonUtil.toJson(task);
            String key = String.valueOf(task.getUserId());

            CompletableFuture<?> future = kafkaTemplate.send(DELIVERY_TOPIC, key, payload);
            future.get(); // Wait for broker ack

            // Update dispatch status
            deliveryTaskMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryTask>()
                            .eq(DeliveryTask::getId, task.getId())
                            .set(DeliveryTask::getDispatchStatus, DispatchStatus.PUBLISHED.name())
                            .set(DeliveryTask::getPublishedAt, LocalDateTime.now()));

            log.info("Delivery task published to Kafka: taskId={}", task.getId());
        } catch (Exception e) {
            log.error("Failed to publish delivery task to Kafka: taskId={}", task.getId(), e);
            // 捕获发布异常以保留已落库的 PENDING 任务，后续补发仍使用相同 taskId。
            // Task stays in DB with dispatch_status=PENDING
            // DispatchRetryJob will pick it up later
        }
    }
}
