package com.pulseflow.campaign.dsl;

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

    /**
     * 构造校验通过结果，并复制可选警告列表。
     */
    public static DslValidationResult ok(List<String> warnings) {
        return DslValidationResult.builder()
                .valid(true)
                .needsConfirmation(false)
                .warnings(warnings == null ? new ArrayList<>() : new ArrayList<>(warnings))
                .build();
    }

    /**
     * 构造需要人工补充字段的结果，复制缺失字段与警告列表，valid 保持 false。
     */
    public static DslValidationResult needsConfirmation(List<String> missingFields, List<String> warnings) {
        return DslValidationResult.builder()
                .valid(false)
                .needsConfirmation(true)
                .missingFields(missingFields == null ? new ArrayList<>() : new ArrayList<>(missingFields))
                .warnings(warnings == null ? new ArrayList<>() : new ArrayList<>(warnings))
                .build();
    }

    /**
     * 构造不可确认的校验失败结果，并复制错误列表。
     */
    public static DslValidationResult invalid(List<String> errors) {
        return DslValidationResult.builder()
                .valid(false)
                .needsConfirmation(false)
                .errors(errors == null ? new ArrayList<>() : new ArrayList<>(errors))
                .build();
    }
}
