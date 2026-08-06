package com.pulseflow.ai.domain.review;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured AI campaign review output (design §10.4).
 *
 * <p>Every highlight / problem / nextAction must include evidenceKeys that
 * exist in the input JSON. The {@link com.pulseflow.ai.guardrail.InsightEvidenceValidator}
 * is reused to enforce this.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewResult {

    private String summary;
    private List<ReviewFinding> highlights;
    private List<ReviewFinding> problems;
    private List<ReviewAction> nextActions;
    private List<String> limitations;
}
