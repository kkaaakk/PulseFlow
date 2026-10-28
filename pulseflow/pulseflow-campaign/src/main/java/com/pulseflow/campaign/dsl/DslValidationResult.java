package com.pulseflow.campaign.dsl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * DSL 校验结果，区分通过、需要人工补充和无效，并携带错误与警告。
 *
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

    /** 构造校验通过结果，并保留需要展示的警告。 */
    public static DslValidationResult ok(List<String> warnings) {
        return DslValidationResult.builder()
                .valid(true)
                .needsConfirmation(false)
                .warnings(warnings == null ? new ArrayList<>() : new ArrayList<>(warnings))
                .build();
    }

    /** 构造待人工补齐字段的结果，保留缺失字段和警告。 */
    public static DslValidationResult needsConfirmation(List<String> missingFields, List<String> warnings) {
        return DslValidationResult.builder()
                .valid(false)
                .needsConfirmation(true)
                .missingFields(missingFields == null ? new ArrayList<>() : new ArrayList<>(missingFields))
                .warnings(warnings == null ? new ArrayList<>() : new ArrayList<>(warnings))
                .build();
    }

    /** 构造 DSL 无效结果，返回阻止草稿确认的错误列表。 */
    public static DslValidationResult invalid(List<String> errors) {
        return DslValidationResult.builder()
                .valid(false)
                .needsConfirmation(false)
                .errors(errors == null ? new ArrayList<>() : new ArrayList<>(errors))
                .build();
    }
}
