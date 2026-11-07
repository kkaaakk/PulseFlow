package com.pulseflow.campaign.delivery;

import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.DeliveryTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class DeliveryService {
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DeliveryTaskPublisher publisher;

    @Transactional
    public void createDeliveryTask(DeliveryTaskDto dto) {
        DeliveryTask task = DeliveryTask.builder().campaignId(dto.getCampaignId()).userId(dto.getUserId())
                .dedupKey(dto.getDedupKey()).triggerEventId(dto.getTriggerEventId()).channel(dto.getChannel())
                .messageContent(dto.getMessageContent()).createdAt(LocalDateTime.now()).build();
        try {
            deliveryTaskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        // Never publish an uncommitted task: a fast consumer could otherwise acknowledge a missing row.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publisher.publish(task); }
        });
    }
}
