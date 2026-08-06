package com.pulseflow.ai.domain.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single condition in a Campaign audience rule.
 *
 * <p>{@code field} MUST exist in {@link com.pulseflow.ai.guardrail.AiFieldRegistry}.
 * {@code operator} MUST be in the field's {@code allowedOperators}.
 * {@code valueType} MUST match the field's {@code valueType}.
 * {@code value} is the threshold (number / string / boolean).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AudienceCondition {

    private String field;
    private String operator;
    private Object value;
    private String valueType;
}
