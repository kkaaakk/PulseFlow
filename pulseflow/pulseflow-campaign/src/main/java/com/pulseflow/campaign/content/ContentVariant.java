package com.pulseflow.campaign.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single proposed content variant.
 *
 * <p>Allowed {@code type} values: DIRECT_BENEFIT / URGENCY / PERSONALIZED.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentVariant {

    private String type;
    private String title;
    private String body;
    private String strategy;
}
