package com.pulseflow.ai.domain.campaign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of validating a {@link CampaignDsl}.
 *
 * <p>{@code valid} drives the draft's {@link DraftStatus#VALIDATED} /
 * {@link DraftStatus#INVALID} / {@link DraftStatus#NEEDS_CONFIRMATION} transition.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DslValidationResult {

    private boolean valid;

    private boolean needsConfirmation;

    @Builder.Default
    private List<String> errors = new ArrayList<>();

    @Builder.Default
    private List<String> missingFields = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    public static DslValidationResult ok(List<String> warnings) {
        return DslValidationResult.builder()
                .valid(true)
                .needsConfirmation(false)
                .warnings(warnings == null ? new ArrayList<>() : new ArrayList<>(warnings))
                .build();
    }

    public static DslValidationResult needsConfirmation(List<String> missingFields, List<String> warnings) {
        return DslValidationResult.builder()
                .valid(false)
                .needsConfirmation(true)
                .missingFields(missingFields == null ? new ArrayList<>() : new ArrayList<>(missingFields))
                .warnings(warnings == null ? new ArrayList<>() : new ArrayList<>(warnings))
                .build();
    }

    public static DslValidationResult invalid(List<String> errors) {
        return DslValidationResult.builder()
                .valid(false)
                .needsConfirmation(false)
                .errors(errors == null ? new ArrayList<>() : new ArrayList<>(errors))
                .build();
    }
}
