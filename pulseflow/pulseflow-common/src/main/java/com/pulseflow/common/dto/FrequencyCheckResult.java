package com.pulseflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrequencyCheckResult {

    private boolean allowed;
    private String reason;
    private boolean retry;
}
