package com.pulseflow.campaign.delivery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.common.dto.DeliveryTaskDto;
import com.pulseflow.common.util.BehaviorWindow;
import com.pulseflow.common.util.DedupKeyUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignExecution;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.mapper.CampaignExecutionMapper;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.DeliveryTaskMapper;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DeliveryService {
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DeliveryTaskPublisher publisher;
    private final CampaignExecutionMapper executions;
    private final CampaignMapper campaigns;
    private final UserProfileMapper profiles;
    private final UserEventMapper userEvents;
    private final PlatformTransactionManager transactions;

    @Transactional
    public void createDeliveryTask(DeliveryTaskDto dto) {
        DeliveryTask task = toTask(dto);
        if (insert(task)) publishAfterCommit(task);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void createDelayedDeliveryTask(DeliveryTaskDto dto, String cartItemId) {
        DeliveryTask task = toTask(dto);
        try {
            if (deliveryTaskMapper.insertIfLatestCartAddition(task, cartItemId) == 1) publishAfterCommit(task);
        } catch (DuplicateKeyException duplicate) {
            // A recovered claim or event replay can materialize the same deduplication key.
        }
    }

    /** Hold the execution lock only while checking durable facts and writing this page. */
    public boolean createScheduledPage(Campaign campaign, CampaignExecution attempt,
                                       List<Long> candidates, BehaviorWindow window) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        List<DeliveryTask> committed = transaction.execute(status -> {
            LocalDateTime startedAt = executions.lockAttemptStartedAt(
                    attempt.getId(), campaign.getId(), attempt.getRetryCount());
            LocalDateTime now = LocalDateTime.now();
            // Check after acquiring the row lock, using the same application time as existing lease rows.
            if (startedAt == null || startedAt.isBefore(now.minusMinutes(10))) return null;
            if (!window.today().equals(now.toLocalDate())) {
                throw new IllegalStateException("Calendar date changed before creating delivery tasks");
            }
            executions.renewLockedAttempt(attempt.getId(), now);
            if (candidates.isEmpty()) return List.of();

            Campaign current = campaigns.selectById(campaign.getId());
            if (current == null || !"ACTIVE".equals(current.getStatus()) || !"SCHEDULED".equals(current.getTriggerType())
                    || current.getStartTime() != null && now.isBefore(current.getStartTime())
                    || current.getEndTime() != null && now.isAfter(current.getEndTime())) return List.of();

            List<Long> enabled = profiles.selectList(new LambdaQueryWrapper<UserProfile>()
                            .select(UserProfile::getUserId).eq(UserProfile::getStatus, 1)
                            .in(UserProfile::getUserId, candidates))
                    .stream().map(UserProfile::getUserId).toList();
            if (enabled.isEmpty()) return List.of();
            var paid = new HashSet<>(userEvents.selectPaidUsers(enabled, window.purchaseStart(), window.endExclusive()));
            List<DeliveryTask> inserted = new ArrayList<>();
            for (Long userId : enabled) {
                if (paid.contains(userId)) continue;
                DeliveryTask task = DeliveryTask.builder().campaignId(current.getId()).userId(userId)
                        .dedupKey(DedupKeyUtil.forScheduled(attempt.getId(), userId)).channel(current.getChannel())
                        .messageContent(current.getMessageTemplate()).createdAt(now).build();
                if (insert(task)) inserted.add(task);
            }
            return inserted;
        });
        if (committed == null) return false;
        // Kafka I/O runs after the commit and after releasing the execution lock and DB connection.
        committed.forEach(publisher::publish);
        return true;
    }

    private DeliveryTask toTask(DeliveryTaskDto dto) {
        return DeliveryTask.builder().campaignId(dto.getCampaignId()).userId(dto.getUserId())
                .dedupKey(dto.getDedupKey()).triggerEventId(dto.getTriggerEventId()).channel(dto.getChannel())
                .messageContent(dto.getMessageContent()).createdAt(LocalDateTime.now()).build();
    }

    private boolean insert(DeliveryTask task) {
        try {
            return deliveryTaskMapper.insert(task) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private void publishAfterCommit(DeliveryTask task) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publisher.publish(task); }
        });
    }
}
