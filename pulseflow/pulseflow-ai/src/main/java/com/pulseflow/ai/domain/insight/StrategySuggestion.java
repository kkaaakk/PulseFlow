package com.pulseflow.ai.domain.insight;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategySuggestion {
    /** OFFER / FREQUENCY / TIMING / CONTENT / SEGMENT */
    private String type;
    private String suggestion;
    private String reason;
    private List<String> evidenceKeys;
}
