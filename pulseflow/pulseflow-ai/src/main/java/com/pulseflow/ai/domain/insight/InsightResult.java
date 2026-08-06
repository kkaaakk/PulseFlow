package com.pulseflow.ai.domain.insight;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured AI insight result. Every finding, suggestion, and risk must
 * reference {@code evidenceKeys} that exist in the input metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsightResult {

    private String summary;
    private List<Finding> findings;
    private List<StrategySuggestion> strategySuggestions;
    private List<String> risks;
}
