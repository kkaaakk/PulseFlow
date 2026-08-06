package com.pulseflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionResult {

    private boolean matched;
    private Long campaignId;
    private Long userId;
    private String dedupKey;
    private String triggerEventId;
    private Map<String, Object> context;
}
