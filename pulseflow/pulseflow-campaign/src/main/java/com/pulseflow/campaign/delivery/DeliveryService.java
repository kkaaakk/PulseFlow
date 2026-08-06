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

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private static final String DELIVERY_TOPIC = "pulseflow.delivery";

    /**
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

            // Outbox: publish to Kafka
            publishToKafka(task);

        } catch (DuplicateKeyException e) {
            log.info("Duplicate delivery task skipped: dedupKey={}", dto.getDedupKey());
        }
    }

    /**
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
            // Task stays in DB with dispatch_status=PENDING
            // DispatchRetryJob will pick it up later
        }
    }
}
