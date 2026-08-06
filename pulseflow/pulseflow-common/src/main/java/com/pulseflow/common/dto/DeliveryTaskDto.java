package com.pulseflow.common.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

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
