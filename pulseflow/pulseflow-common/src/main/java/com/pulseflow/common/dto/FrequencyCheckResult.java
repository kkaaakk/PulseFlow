package com.pulseflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 频控校验结果，供触达流程判断放行或拒绝原因。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrequencyCheckResult {

    private boolean allowed;
    private String reason;
    private boolean retry;
}
