package com.pulseflow.ai.domain.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Root DSL object produced by AI /parse and validated before draft save.
 *
 * <p>v1 constraints (enforced by {@link com.pulseflow.ai.guardrail.CampaignDslValidator}):</p>
 * <ul>
 *   <li>{@code schemaVersion} = 1</li>
 *   <li>{@code audience.logic} ∈ {AND, OR}</li>
 *   <li>1 ≤ {@code audience.conditions.size()} ≤ 10</li>
 *   <li>no nested groups (one level only)</li>
 *   <li>{@code schedule.type} = ONCE</li>
 *   <li>{@code channel} ∈ existing ChannelType enum</li>
 *   <li>{@code frequencyCap.maxTimes} &gt; 0 and {@code windowHours} &gt; 0</li>
 *   <li>{@code promotionFacts} may be empty only when status=NEEDS_CONFIRMATION</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CampaignDsl {

    private Integer schemaVersion;

    private String campaignName;

    /** CONVERSION / RETENTION / ACTIVATION / BRANDING. */
    private String objective;

    private AudienceGroup audience;

    /** IN_APP / PUSH / EMAIL. */
    private String channel;

    private CampaignSchedule schedule;

    private FrequencyCap frequencyCap;

    private List<PromotionFact> promotionFacts;

    /** Fields AI couldn't fill (e.g. missing time / promotion). */
    private List<String> missingFields;

    /** Soft warnings (e.g. tight frequency, large audience). */
    private List<String> warnings;
}
