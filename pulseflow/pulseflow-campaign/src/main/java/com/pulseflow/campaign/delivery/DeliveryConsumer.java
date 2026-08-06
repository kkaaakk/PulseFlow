package com.pulseflow.campaign.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseflow.common.enums.ChannelType;
import com.pulseflow.common.enums.TaskStatus;
import com.pulseflow.entity.DeliveryRecord;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.entity.InAppMessage;
import com.pulseflow.entity.PushRecord;
import com.pulseflow.mapper.DeliveryRecordMapper;
import com.pulseflow.mapper.DeliveryTaskMapper;
import com.pulseflow.mapper.InAppMessageMapper;
import com.pulseflow.mapper.PushRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryConsumer {

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DeliveryRecordMapper deliveryRecordMapper;
    private final InAppMessageMapper inAppMessageMapper;
    private final PushRecordMapper pushRecordMapper;
    private final FrequencyControlService frequencyControlService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @KafkaListener(topics = "pulseflow.delivery", groupId = "pulseflow-delivery-group",
            concurrency = "2")
    public void consume(ConsumerRecord<String, String> record) {
        DeliveryTask task = parseTask(record.value());
        if (task == null) return;

        log.info("Consuming delivery task: id={}, userId={}, campaignId={}",
                task.getId(), task.getUserId(), task.getCampaignId());

        // Step 1: Claim task (PENDING -> PROCESSING) via conditional UPDATE
        int claimed = deliveryTaskMapper.tryClaim(task.getId());
        if (claimed != 1) {
            log.info("Task {} already claimed by another instance", task.getId());
            return;
        }

        try {
            // Step 2: Frequency control check (Lua atomic: judge + reserve)
            FrequencyControlService.FreqResult freqResult = frequencyControlService.check(task);
            if (!freqResult.isAllowed()) {
                deliveryTaskMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryTask>()
                                .eq(DeliveryTask::getId, task.getId())
                                .set(DeliveryTask::getStatus, TaskStatus.CANCELLED.name())
                                .set(DeliveryTask::getLastError, freqResult.getReason()));
                log.info("Task {} cancelled by frequency control: {}",
                        task.getId(), freqResult.getReason());
                return;
            }

            // Step 3: Send via channel (with business_key idempotency per channel)
            sendViaChannel(task);

            // Step 4: Mark as SENT
            deliveryTaskMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryTask>()
                            .eq(DeliveryTask::getId, task.getId())
                            .set(DeliveryTask::getStatus, TaskStatus.SENT.name()));

            log.info("Delivery task {} sent successfully", task.getId());

        } catch (Exception e) {
            log.error("Delivery task {} failed: {}", task.getId(), e.getMessage(), e);
            deliveryTaskMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryTask>()
                            .eq(DeliveryTask::getId, task.getId())
                            .set(DeliveryTask::getStatus, TaskStatus.WAIT_RETRY.name())
                            .set(DeliveryTask::getNextRetryAt, LocalDateTime.now().plusMinutes(5))
                            .set(DeliveryTask::getLastError,
                                    e.getMessage() != null
                                            ? e.getMessage().substring(0, Math.min(512, e.getMessage().length()))
                                            : "Unknown error")
                            .setSql("retry_count = retry_count + 1"));
        }
    }

    /**
     * Channel-specific send with business_key idempotency.
     *
     * <p>Per design §4.3:
     * <ul>
     *   <li><b>IN_APP / PUSH</b>: INSERT into the channel table with
     *       business_key = taskId (UK guarantees no duplicate); on
     *       DuplicateKeyException the previous send already succeeded, so we
     *       just backfill delivery_record + treat as SENT.</li>
     *   <li><b>EMAIL</b>: external SMTP cannot guarantee idempotency, so we
     *       INSERT delivery_record FIRST (UK task_id), then attempt the send;
     *       on send failure mark record FAILED + task WAIT_RETRY. Retry hits
     *       the UK and skips re-insert, only updating status.</li>
     * </ul>
     */
    private void sendViaChannel(DeliveryTask task) {
        Long taskId = task.getId();
        String channel = task.getChannel();

        if (ChannelType.IN_APP.name().equals(channel)) {
            sendInApp(task);
        } else if (ChannelType.PUSH.name().equals(channel)) {
            sendPush(task);
        } else if (ChannelType.EMAIL.name().equals(channel)) {
            sendEmail(task);
        } else {
            throw new IllegalStateException("Unknown channel: " + channel);
        }
    }

    /**
     * 站内信：INSERT in_app_message (business_key=taskId UK)。
     * 命中 UK → 之前已发送成功，补写 delivery_record 后视为 SENT。
     */
    private void sendInApp(DeliveryTask task) {
        Long taskId = task.getId();
        try {
            InAppMessage msg = InAppMessage.builder()
                    .businessKey(taskId)
                    .userId(task.getUserId())
                    .campaignId(task.getCampaignId())
                    .content(task.getMessageContent())
                    .build();
            inAppMessageMapper.insert(msg);
            log.info("In-app message sent: taskId={}, userId={}", taskId, task.getUserId());
        } catch (DuplicateKeyException e) {
            log.info("In-app message already exists for task {} (idempotent skip)", taskId);
        }
        upsertDeliveryRecord(task, "SENT", null);
    }

    /**
     * 模拟 Push：INSERT push_record (business_key=taskId UK)。
     * 命中 UK → 之前已发送成功，补写 delivery_record 后视为 SENT。
     */
    private void sendPush(DeliveryTask task) {
        Long taskId = task.getId();
        try {
            PushRecord push = PushRecord.builder()
                    .businessKey(taskId)
                    .userId(task.getUserId())
                    .campaignId(task.getCampaignId())
                    .title("PulseFlow")
                    .content(task.getMessageContent())
                    .build();
            pushRecordMapper.insert(push);
            log.info("Push sent: taskId={}, userId={}", taskId, task.getUserId());
        } catch (DuplicateKeyException e) {
            log.info("Push record already exists for task {} (idempotent skip)", taskId);
        }
        upsertDeliveryRecord(task, "SENT", null);
    }

    /**
     * 邮件：先 INSERT delivery_record (UK task_id)，再"发送"（MVP 模拟）。
     * 外部 SMTP 无法绝对保证幂等，因此诚实降级——先持久化记录再发送，
     * 发送失败标记 FAILED + 抛异常触发 WAIT_RETRY。重试时 UK 已存在，
     * 只更新状态，不重复插入。
     */
    private void sendEmail(DeliveryTask task) {
        Long taskId = task.getId();
        try {
            // 先写记录（UK task_id 保证幂等）
            DeliveryRecord record = DeliveryRecord.builder()
                    .taskId(taskId)
                    .userId(task.getUserId())
                    .campaignId(task.getCampaignId())
                    .channel(ChannelType.EMAIL.name())
                    .status("SENT")
                    .sentAt(LocalDateTime.now())
                    .build();
            try {
                deliveryRecordMapper.insert(record);
            } catch (DuplicateKeyException e) {
                log.info("Email delivery_record already exists for task {}, updating status", taskId);
            }

            // 模拟外部 SMTP 发送（MVP：写日志即视为成功）
            // 生产环境此处调用 SMTP 客户端；失败则抛异常触发 WAIT_RETRY
            log.info("Email sent (simulated): taskId={}, userId={}", taskId, task.getUserId());
        } catch (Exception e) {
            // 发送失败：更新记录状态为 FAILED
            deliveryRecordMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryRecord>()
                            .eq(DeliveryRecord::getTaskId, taskId)
                            .set(DeliveryRecord::getStatus, "FAILED")
                            .set(DeliveryRecord::getErrorMsg,
                                    e.getMessage() != null
                                            ? e.getMessage().substring(0, Math.min(512, e.getMessage().length()))
                                            : "Unknown error"));
            throw e;
        }
    }

    /**
     * 写 delivery_record (UK task_id)，IN_APP/PUSH 用。
     * 命中 UK 说明之前已写过（如重试场景），跳过即可。
     */
    private void upsertDeliveryRecord(DeliveryTask task, String status, String errorMsg) {
        try {
            DeliveryRecord record = DeliveryRecord.builder()
                    .taskId(task.getId())
                    .userId(task.getUserId())
                    .campaignId(task.getCampaignId())
                    .channel(task.getChannel())
                    .status(status)
                    .sentAt(LocalDateTime.now())
                    .errorMsg(errorMsg)
                    .build();
            deliveryRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // record 已存在（重试），跳过
        }
    }

    private DeliveryTask parseTask(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, DeliveryTask.class);
        } catch (Exception e) {
            log.error("Failed to parse delivery task JSON", e);
            return null;
        }
    }
}
