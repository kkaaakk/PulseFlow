package com.pulseflow.campaign.delivery;

import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.DeliveryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTaskPublisher {
    private final DeliveryTaskMapper tasks;
    private final KafkaTemplate<String,String> kafka;

    private final PlatformTransactionManager transactions;

    public boolean publish(DeliveryTask task) {
        try {
            kafka.send("pulseflow.delivery", String.valueOf(task.getUserId()), JsonUtil.toJson(task))
                    .get(10, TimeUnit.SECONDS);
            TransactionTemplate acknowledgement = new TransactionTemplate(transactions);
            acknowledgement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            acknowledgement.executeWithoutResult(status ->
                    tasks.markPublishedForAttempt(task.getId(), task.getRetryCount(), task.getStatus(), LocalDateTime.now()));
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Dispatch remains pending for task {}", task.getId(), e);
            return false;
        }
    }
}
