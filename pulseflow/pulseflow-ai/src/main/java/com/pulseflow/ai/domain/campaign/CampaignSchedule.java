package com.pulseflow.ai.domain.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Schedule block. v1 supports type=ONCE only.
 *
 * <p>{@code sendAt} MUST be ISO-8601 with offset (e.g.
 * {@code 2026-08-03T20:00:00+08:00}). {@code timezone} MUST be a valid
 * ZoneId (e.g. {@code Asia/Shanghai}).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CampaignSchedule {

    /** "ONCE" only in v1. */
    private String type;

    private String sendAt;

    private String timezone;
}
