package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.domain.review.ReviewAction;
import com.pulseflow.ai.domain.review.ReviewFinding;
import com.pulseflow.ai.domain.review.ReviewResult;
import com.pulseflow.ai.support.AiOutputInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link ReviewEvidenceValidator}.
 */
class ReviewEvidenceValidatorTest {

    private final ReviewEvidenceValidator validator = new ReviewEvidenceValidator();

    private String inputJson() {
        Map<String, Object> input = Map.of(
                "campaignId", 1024L,
                "objective", "CONVERSION",
                "metrics", Map.of(
                        "sentCount", 18000L,
                        "deliveryRate", 0.97,
                        "clickRate", 0.126,
                        "conversionRate", 0.041,
                        "unsubscribeRate", 0.003),
                "historicalBaseline", Map.of(
                        "clickRate", 0.091,
                        "conversionRate", 0.038),
                "contentVariants", List.of());
        return com.pulseflow.common.util.JsonUtil.toJson(input);
    }

    @Test
    @DisplayName("valid highlights and problems pass")
    void validFindings() {
        InsightResultHelper.assertThat(
                ReviewResult.builder()
                        .summary("本次活动点击表现为12.6%。")
                        .highlights(List.of(ReviewFinding.builder()
                                .title("点击率提升")
                                .description("本次点击率为12.6%。")
                                .evidenceKeys(List.of("metrics.clickRate"))
                                .build()))
                        .problems(List.of(ReviewFinding.builder()
                                .title("转化损耗")
                                .description("转化率为4.1%。")
                                .evidenceKeys(List.of("metrics.conversionRate"))
                                .build()))
                        .build())
                .isValidAgainst(inputJson(), validator);
    }

    @Test
    @DisplayName("highlight with unknown evidenceKey is dropped")
    void unknownKey() {
        ReviewResult result = ReviewResult.builder()
                .summary("ok")
                .highlights(List.of(ReviewFinding.builder()
                        .title("bogus")
                        .description("描述")
                        .evidenceKeys(List.of("metrics.nonExistent"))
                        .build()))
                .build();
        ReviewResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getHighlights()).isEmpty();
    }

    @Test
    @DisplayName("problem with fabricated number is dropped")
    void fabricatedProblem() {
        ReviewResult result = ReviewResult.builder()
                .summary("ok")
                .problems(List.of(ReviewFinding.builder()
                        .title("fake")
                        .description("退货率高达95%。") // 95% not in input
                        .evidenceKeys(List.of("metrics.conversionRate"))
                        .build()))
                .build();
        ReviewResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getProblems()).isEmpty();
    }

    @Test
    @DisplayName("nextAction with invalid evidence is dropped")
    void invalidAction() {
        ReviewResult result = ReviewResult.builder()
                .summary("ok")
                .nextActions(List.of(
                        ReviewAction.builder()
                                .action("用直接利益型")
                                .reason("点击率为12.6%")
                                .evidenceKeys(List.of("metrics.clickRate"))
                                .build(),
                        ReviewAction.builder()
                                .action("bogus")
                                .reason("描述")
                                .evidenceKeys(List.of("metrics.bogus"))
                                .build()))
                .build();
        ReviewResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getNextActions()).hasSize(1);
    }

    @Test
    @DisplayName("summary with fabricated number throws")
    void summaryFabricated() {
        ReviewResult result = ReviewResult.builder()
                .summary("转化率高达99%。") // 99% not in input
                .build();
        assertThatThrownBy(() -> validator.validate(inputJson(), result))
                .isInstanceOf(AiOutputInvalidException.class)
                .hasMessageContaining("summary references numbers");
    }

    @Test
    @DisplayName("contentVariants is accepted as evidenceKey")
    void contentVariantsKey() {
        ReviewResult result = ReviewResult.builder()
                .summary("ok")
                .nextActions(List.of(ReviewAction.builder()
                        .action("用直接利益型")
                        .reason("该版本表现最好")
                        .evidenceKeys(List.of("contentVariants"))
                        .build()))
                .build();
        ReviewResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getNextActions()).hasSize(1);
    }

    @Test
    @DisplayName("percentage point difference (0.3pp) is accepted as derived")
    void percentagePointDifference() {
        // input conversionRate=0.041, baseline=0.038 → diff 0.003 = 0.3pp
        ReviewResult result = ReviewResult.builder()
                .summary("转化率提高0.3个百分点。")
                .problems(List.of(ReviewFinding.builder()
                        .title("转化损耗")
                        .description("转化率为4.1%，与基线3.8%相比提升0.3个百分点。")
                        .evidenceKeys(List.of("metrics.conversionRate", "historicalBaseline.conversionRate"))
                        .build()))
                .build();
        ReviewResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getProblems()).hasSize(1);
    }

    @Test
    @DisplayName("relative change (7.9%) is accepted as derived")
    void relativeChangePercent() {
        // (0.041 - 0.038) / 0.038 ≈ 0.0789 → 7.9%
        ReviewResult result = ReviewResult.builder()
                .summary("转化率相对提升7.9%。")
                .highlights(List.of(ReviewFinding.builder()
                        .title("相对提升")
                        .description("本次转化率4.1%较基线3.8%相对提升7.9%。")
                        .evidenceKeys(List.of("metrics.conversionRate", "historicalBaseline.conversionRate"))
                        .build()))
                .build();
        ReviewResult validated = validator.validate(inputJson(), result);
        assertThat(validated.getHighlights()).hasSize(1);
    }

    @Test
    @DisplayName("fabricated number that is neither direct nor derived is rejected")
    void fabricatedNumberStillRejected() {
        // 99% is not in input nor a derived diff/relative-change
        ReviewResult result = ReviewResult.builder()
                .summary("转化率高达99%。")
                .build();
        assertThatThrownBy(() -> validator.validate(inputJson(), result))
                .isInstanceOf(AiOutputInvalidException.class);
    }

    /** Tiny helper to keep the fluent assertion readable. */
    static final class InsightResultHelper {
        static InsightResultHelper assertThat(ReviewResult result) {
            return new InsightResultHelper(result);
        }

        private final ReviewResult result;

        InsightResultHelper(ReviewResult result) {
            this.result = result;
        }

        void isValidAgainst(String inputJson, ReviewEvidenceValidator validator) {
            ReviewResult validated = validator.validate(inputJson, result);
            org.assertj.core.api.Assertions.assertThat(validated.getHighlights()).isNotEmpty();
            org.assertj.core.api.Assertions.assertThat(validated.getProblems()).isNotEmpty();
        }
    }
}
