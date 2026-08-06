package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.domain.insight.Finding;
import com.pulseflow.ai.domain.insight.InsightResult;
import com.pulseflow.ai.domain.insight.StrategySuggestion;
import com.pulseflow.ai.support.AiOutputInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link InsightEvidenceValidator}.
 *
 * <p>Validates that the evidence-key guardrail drops fabricated findings and
 * rejects summaries with invented numbers.</p>
 */
class InsightEvidenceValidatorTest {

    private final InsightEvidenceValidator validator = new InsightEvidenceValidator();

    private String inputJson() {
        // Mirror the AudienceInsightService.buildLlmInput shape
        Map<String, Object> input = Map.of(
                "metrics", Map.of(
                        "audienceCount", 18420L,
                        "activeRate7d", 0.78,
                        "averageSpend30d", 523.50,
                        "cartWithoutPurchaseRate", 0.42,
                        "priceSensitiveRate", 0.35,
                        "churnRiskRate", 0.18),
                "baseline", Map.of(
                        "activeRate7d", 0.59,
                        "averageSpend30d", 410.20,
                        "cartWithoutPurchaseRate", 0.28));
        return com.pulseflow.common.util.JsonUtil.toJson(input);
    }

    @Test
    @DisplayName("valid findings with correct evidenceKeys pass")
    void validFindings() {
        InsightResult result = InsightResult.builder()
                .summary("该人群活跃率为78%。")
                .findings(List.of(
                        Finding.builder()
                                .title("活跃度高")
                                .description("活跃率为78%。")
                                .evidenceKeys(List.of("metrics.activeRate7d"))
                                .importance("HIGH")
                                .build()))
                .build();
        InsightResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getFindings()).hasSize(1);
    }

    @Test
    @DisplayName("finding with unknown evidenceKey is dropped")
    void unknownEvidenceKey() {
        InsightResult result = InsightResult.builder()
                .summary("ok")
                .findings(List.of(
                        Finding.builder()
                                .title("bogus")
                                .description("描述")
                                .evidenceKeys(List.of("metrics.nonExistentField"))
                                .importance("HIGH")
                                .build()))
                .build();
        InsightResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getFindings()).isEmpty();
    }

    @Test
    @DisplayName("finding with no evidenceKeys is dropped")
    void noEvidenceKeys() {
        InsightResult result = InsightResult.builder()
                .summary("ok")
                .findings(List.of(
                        Finding.builder()
                                .title("no evidence")
                                .description("描述")
                                .evidenceKeys(List.of())
                                .importance("HIGH")
                                .build()))
                .build();
        InsightResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getFindings()).isEmpty();
    }

    @Test
    @DisplayName("finding with fabricated number in description is dropped")
    void fabricatedNumber() {
        // 99% is not in input
        InsightResult result = InsightResult.builder()
                .summary("ok")
                .findings(List.of(
                        Finding.builder()
                                .title("fake stat")
                                .description("活跃率高达99%。")
                                .evidenceKeys(List.of("metrics.activeRate7d"))
                                .importance("HIGH")
                                .build()))
                .build();
        InsightResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getFindings()).isEmpty();
    }

    @Test
    @DisplayName("summary with fabricated number throws")
    void summaryFabricatedNumber() {
        InsightResult result = InsightResult.builder()
                .summary("该人群转化率为95%。") // 95% not in input
                .build();
        assertThatThrownBy(() -> validator.validate(inputJson(), result))
                .isInstanceOf(AiOutputInvalidException.class)
                .hasMessageContaining("summary references numbers");
    }

    @Test
    @DisplayName("strategy suggestion with invalid evidence is dropped")
    void strategyInvalidEvidence() {
        InsightResult result = InsightResult.builder()
                .summary("ok")
                .strategySuggestions(List.of(
                        StrategySuggestion.builder()
                                .type("OFFER")
                                .suggestion("降价")
                                .reason("价格敏感用户占比35%")
                                .evidenceKeys(List.of("metrics.priceSensitiveRate"))
                                .build(),
                        StrategySuggestion.builder()
                                .type("FREQUENCY")
                                .suggestion("bogus")
                                .reason("描述")
                                .evidenceKeys(List.of("metrics.bogusField"))
                                .build()))
                .build();
        InsightResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getStrategySuggestions()).hasSize(1);
        assertThat(validated.getStrategySuggestions().get(0).getSuggestion()).isEqualTo("降价");
    }

    @Test
    @DisplayName("percentage in description matches ratio in input")
    void percentageMatchesRatio() {
        // input has activeRate7d=0.78; "78%" should match
        InsightResult result = InsightResult.builder()
                .summary("活跃率78%。")
                .findings(List.of(
                        Finding.builder()
                                .title("active")
                                .description("活跃率78%，高于基线59%。")
                                .evidenceKeys(List.of("metrics.activeRate7d", "baseline.activeRate7d"))
                                .importance("HIGH")
                                .build()))
                .build();
        InsightResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getFindings()).hasSize(1);
    }
}
