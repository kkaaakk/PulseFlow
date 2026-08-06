package com.pulseflow.ai.domain.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Frequency cap. {@code maxTimes} MUST be &gt; 0; {@code windowHours} MUST be &gt; 0.
 *
 * <p>Mapped to {@code campaign.user_daily_limit} (when windowHours=24) or
 * {@code campaign.campaign_weekly_limit} (when windowHours=168) on confirmation.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FrequencyCap {

    private Integer maxTimes;
    private Integer windowHours;
}
