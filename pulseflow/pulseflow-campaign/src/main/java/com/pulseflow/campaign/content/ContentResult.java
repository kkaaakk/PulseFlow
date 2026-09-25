package com.pulseflow.campaign.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured Campaign content with differentiated variants.
 *
 * <p>Variant types are fixed: DIRECT_BENEFIT, URGENCY, PERSONALIZED.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentResult {

    private List<ContentVariant> variants;
}
