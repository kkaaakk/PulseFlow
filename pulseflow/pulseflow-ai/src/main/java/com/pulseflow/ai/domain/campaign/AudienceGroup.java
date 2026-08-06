package com.pulseflow.ai.domain.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Audience rule: AND/OR of conditions. v1 supports one level (logic + conditions).
 * Nested groups would be a future extension and are rejected by the validator
 * to keep rule complexity bounded.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AudienceGroup {

    /** "AND" or "OR". v1 requires AND/OR at top level only. */
    private String logic;

    private List<AudienceCondition> conditions;
}
