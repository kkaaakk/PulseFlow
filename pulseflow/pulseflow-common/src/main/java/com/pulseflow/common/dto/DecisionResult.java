package com.pulseflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 规则决策结果，保存匹配与执行相关信息。 */
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
