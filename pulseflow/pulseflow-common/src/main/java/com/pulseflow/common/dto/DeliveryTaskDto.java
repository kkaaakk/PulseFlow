package com.pulseflow.common.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 跨决策和触达服务传递的任务参数，包含用户、活动及幂等键。 */
@Data
@Builder
public class DeliveryTaskDto {

    private Long campaignId;
    private Long userId;
    private String dedupKey;
    private String triggerEventId;
    private String channel;
    private String messageContent;
    private LocalDateTime createdAt;
}
