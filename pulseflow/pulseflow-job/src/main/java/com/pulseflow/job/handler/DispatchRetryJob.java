package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.common.enums.DispatchStatus;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.campaign.delivery.DeliveryTaskPublisher;
import com.pulseflow.mapper.DeliveryTaskMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchRetryJob {

    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DeliveryTaskPublisher publisher;

    /**
     * 每次扫描最多一百条创建超过三十秒且仍未确认发布的任务（包括已完成但派发确认写入失败），等待 Kafka 确认后标记 PUBLISHED；失败留待下轮重试。
     */
    @XxlJob("dispatchRetryJob")
    public void execute() {
        log.info("DispatchRetryJob started");

        LocalDateTime thirtySecondsAgo = LocalDateTime.now().minusSeconds(30);

        List<DeliveryTask> pendingDispatch = deliveryTaskMapper.selectList(
                new LambdaQueryWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getDispatchStatus, DispatchStatus.PENDING.name())
                        .lt(DeliveryTask::getCreatedAt, thirtySecondsAgo)
                        .orderByAsc(DeliveryTask::getId)
                        .last("LIMIT 100"));

        int successCount = 0;
        for (DeliveryTask task : pendingDispatch) {
            try {
                if (publisher.publish(task)) successCount++;
            } catch (Exception e) {
                log.error("Dispatch retry failed for task {}: {}", task.getId(), e.getMessage());
            }
        }

        log.info("DispatchRetryJob completed: {}/{} dispatched", successCount, pendingDispatch.size());
    }
}
