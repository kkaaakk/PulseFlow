package com.pulseflow.campaign.delivery;

import com.pulseflow.entity.*;
import com.pulseflow.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

/** Channel facts survive a failed task update. Recovery never sends or checks quota. */
@Service
@RequiredArgsConstructor
public class DeliveryRecoveryService {
    private final DeliveryTaskMapper tasks;
    private final InAppMessageMapper inApp;
    private final PushRecordMapper push;

    /** Keep the claim valid through the database-backed channel effect, separately from task finalization. */
    @Transactional
    public LocalDateTime storeChannelReceipt(DeliveryTask task, LocalDateTime attemptedAt) {
        if (tasks.lockChannelClaim(task.getId(), task.getProcessingAt()) == null) return null;
        try {
            if ("IN_APP".equals(task.getChannel())) {
                inApp.insert(InAppMessage.builder().businessKey(task.getId()).userId(task.getUserId())
                        .campaignId(task.getCampaignId()).content(task.getMessageContent()).createdAt(attemptedAt).build());
            } else {
                push.insert(PushRecord.builder().businessKey(task.getId()).userId(task.getUserId())
                        .campaignId(task.getCampaignId()).title("PulseFlow").content(task.getMessageContent())
                        .pushedAt(attemptedAt).build());
            }
        } catch (DuplicateKeyException alreadySent) {
            // Preserve the original receipt and its time.
        }
        LocalDateTime sentAt = successfulSendTime(task);
        if (sentAt == null) throw new IllegalStateException("CHANNEL_SUCCESS_TIME_MISSING");
        return sentAt;
    }

    public LocalDateTime successfulSendTime(DeliveryTask task) {
        if ("IN_APP".equals(task.getChannel())) {
            InAppMessage m = inApp.selectByTaskId(task.getId());
            if (m == null) return null;
            validate(task, m.getUserId(), m.getCampaignId(), m.getCreatedAt());
            return m.getCreatedAt();
        }
        if ("PUSH".equals(task.getChannel())) {
            PushRecord m = push.selectByTaskId(task.getId());
            if (m == null) return null;
            validate(task, m.getUserId(), m.getCampaignId(), m.getPushedAt());
            return m.getPushedAt();
        }
        return null;
    }

    public boolean recover(DeliveryTask task) {
        if ("CANCELLED".equals(task.getStatus())) return false;
        LocalDateTime time = successfulSendTime(task);
        if (time == null) return false;
        complete(task, time);
        return true;
    }

    public void complete(DeliveryTask task, LocalDateTime time) {
        if (time == null) throw new IllegalStateException("CHANNEL_SUCCESS_TIME_MISSING");
        if (tasks.markSent(task.getId(), time) == 1) return;
        DeliveryTask current = tasks.selectById(task.getId());
        if (current != null && "SENT".equals(current.getStatus()) && current.getSentAt() != null) return;
        throw new IllegalStateException("TASK_SUCCESS_NOT_PERSISTED");
    }

    private void validate(DeliveryTask task, Long user, Long campaign, LocalDateTime time) {
        if (!Objects.equals(task.getUserId(), user) || !Objects.equals(task.getCampaignId(), campaign) || time == null)
            throw new ReceiptConflictException();
    }
    public static class ReceiptConflictException extends IllegalStateException {
        public ReceiptConflictException() { super("CHANNEL_RECEIPT_MISMATCH"); }
    }

}
