package com.pulseflow.campaign.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseflow.entity.*;
import com.pulseflow.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryConsumer {
    private final DeliveryTaskMapper tasks;
    private final FrequencyControlService frequency;
    private final DeliveryRecoveryService recovery;
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @KafkaListener(topics="pulseflow.delivery", groupId="pulseflow-delivery-group", concurrency="2")
    public void consume(ConsumerRecord<String, String> record) {
        Long id;
        try {
            id = JSON.readValue(record.value(), DeliveryTask.class).getId();
            if (id == null) throw new IllegalArgumentException("task id missing");
        } catch (Exception e) {
            log.error("Invalid delivery task payload", e);
            return;
        }
        // Kafka is only a wake-up. Ownership, content, channel and state come from MySQL.
        LocalDateTime claim = now();
        if (tasks.tryClaimAt(id, claim) != 1) return;
        DeliveryTask task = tasks.selectById(id);
        if (task == null || !"PROCESSING".equals(task.getStatus()) || !claim.equals(task.getProcessingAt())) return;
        try {
            // Must precede frequency checks: an already delivered task cannot be cancelled by quota.
            if (recovery.recover(task)) return;
            if ("EMAIL".equals(task.getChannel()) && task.getFirstSendAttemptAt() != null) {
                tasks.finishAttempt(id, task.getProcessingAt(), "FAILED", null, "EMAIL_SEND_OUTCOME_UNKNOWN", 0);
                return;
            }
            if (!java.util.Set.of("IN_APP", "PUSH", "EMAIL").contains(task.getChannel())) {
                tasks.finishAttempt(id, task.getProcessingAt(), "FAILED", null, "UNKNOWN_CHANNEL", 0);
                return;
            }
            var allowed = frequency.check(task);
            if (!allowed.isAllowed()) {
                tasks.finishAttempt(id, task.getProcessingAt(), "CANCELLED", null, allowed.getReason(), 0);
                return;
            }
            if ("EMAIL".equals(task.getChannel())) {
                if (tasks.reserveEmailAttempt(id, task.getProcessingAt(), now()) != 1) return;
                // MVP: logging is the send. No SMTP or external exactly-once guarantee.
                log.info("Email sent (simulated): taskId={}, userId={}", id, task.getUserId());
                recovery.complete(task, now());
            } else {
                LocalDateTime attemptedAt = now();
                if (tasks.markAttempt(id, claim, attemptedAt) != 1) return;
                LocalDateTime sentAt = recovery.storeChannelReceipt(task, attemptedAt);
                if (sentAt != null) recovery.complete(task, sentAt);
            }
        } catch (Exception e) {
            String error = e.getMessage() == null ? "Unknown error" : e.getMessage();
            boolean emailAttempted = "EMAIL".equals(task.getChannel())
                    && tasks.selectById(id).getFirstSendAttemptAt() != null;
            // If this write also fails, throw to Kafka. The timeout job can recover PROCESSING.
            boolean terminal = emailAttempted || e instanceof DeliveryRecoveryService.ReceiptConflictException;
            tasks.finishAttempt(id, task.getProcessingAt(), terminal ? "FAILED" : "WAIT_RETRY",
                    terminal ? null : LocalDateTime.now().plusMinutes(5),
                    emailAttempted ? "EMAIL_SEND_OUTCOME_UNKNOWN" : error.substring(0, Math.min(512, error.length())), 1);
            log.warn("Delivery {} needs recovery: {}", id, error);
        }
    }
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

}
