package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.common.enums.DispatchStatus;
import com.pulseflow.common.enums.TaskStatus;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.DeliveryTaskMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchRetryJob {

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private static final String DELIVERY_TOPIC = "pulseflow.delivery";

    @XxlJob("dispatchRetryJob")
    public void execute() {
        log.info("DispatchRetryJob started");

        LocalDateTime thirtySecondsAgo = LocalDateTime.now().minusSeconds(30);

        List<DeliveryTask> pendingDispatch = deliveryTaskMapper.selectList(
                new LambdaQueryWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getDispatchStatus, DispatchStatus.PENDING.name())
                        .lt(DeliveryTask::getCreatedAt, thirtySecondsAgo)
                        .eq(DeliveryTask::getStatus, TaskStatus.PENDING.name())
                        .orderByAsc(DeliveryTask::getId)
                        .last("LIMIT 100"));

        int successCount = 0;
        for (DeliveryTask task : pendingDispatch) {
            try {
                String payload = JsonUtil.toJson(task);
                String key = String.valueOf(task.getUserId());

                kafkaTemplate.send(DELIVERY_TOPIC, key, payload).get();

                deliveryTaskMapper.update(null,
                        new LambdaUpdateWrapper<DeliveryTask>()
                                .eq(DeliveryTask::getId, task.getId())
                                .set(DeliveryTask::getDispatchStatus, DispatchStatus.PUBLISHED.name())
                                .set(DeliveryTask::getPublishedAt, LocalDateTime.now()));

                successCount++;
            } catch (Exception e) {
                log.error("Dispatch retry failed for task {}: {}", task.getId(), e.getMessage());
            }
        }

        log.info("DispatchRetryJob completed: {}/{} dispatched", successCount, pendingDispatch.size());
    }
}
