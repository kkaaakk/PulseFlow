package com.pulseflow.campaign.delivery;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.DeliveryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTaskPublisher {
    private final DeliveryTaskMapper tasks;
    private final KafkaTemplate<String,String> kafka;

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean publish(DeliveryTask task) {
        try {
            kafka.send("pulseflow.delivery", String.valueOf(task.getUserId()), JsonUtil.toJson(task)).get();
            tasks.update(null, new LambdaUpdateWrapper<DeliveryTask>().eq(DeliveryTask::getId, task.getId())
                    .eq(DeliveryTask::getDispatchStatus, "PENDING")
                    .set(DeliveryTask::getDispatchStatus, "PUBLISHED")
                    .set(DeliveryTask::getPublishedAt, LocalDateTime.now()));
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Dispatch remains pending for task {}", task.getId(), e);
            return false;
        }
    }
}
