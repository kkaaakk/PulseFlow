package com.pulseflow.ai.guardrail;

import com.pulseflow.ai.domain.campaign.AudienceCondition;
import com.pulseflow.ai.domain.campaign.AudienceGroup;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.CampaignSchedule;
import com.pulseflow.ai.domain.campaign.DslValidationResult;
import com.pulseflow.ai.domain.campaign.FrequencyCap;
import com.pulseflow.ai.domain.campaign.PromotionFact;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link CampaignDslValidator}.
 *
 * <p>Uses a manually-initialised {@link AiFieldRegistry} (no Spring context).</p>
 */
class CampaignDslValidatorTest {

    private static AiFieldRegistry registry;
    private static CampaignDslValidator validator;

    @BeforeAll
    static void setUp() {
        registry = new AiFieldRegistry();
        // Manually invoke @PostConstruct (no Spring in unit test)
        registry.init();
        validator = new CampaignDslValidator(registry);
    }

    private CampaignDsl validDsl() {
        return CampaignDsl.builder()
                .schemaVersion(1)
                .campaignName("测试活动")
                .objective("CONVERSION")
                .audience(AudienceGroup.builder()
                        .logic("AND")
                        .conditions(new java.util.ArrayList<>(List.of(
                                AudienceCondition.builder()
                                        .field("activeDays7d")
                                        .operator("GTE")
                                        .valueType("INTEGER")
                                        .value(5)
                                        .build(),
                                AudienceCondition.builder()
                                        .field("spend30d")
                                        .operator("GT")
                                        .valueType("DECIMAL")
                                        .value(500)
                                        .build())))
                        .build())
                .channel("IN_APP")
                .schedule(CampaignSchedule.builder()
                        .type("ONCE")
                        .sendAt(OffsetDateTime.now().plusDays(1).toString())
                        .timezone("Asia/Shanghai")
                        .build())
                .frequencyCap(FrequencyCap.builder()
                        .maxTimes(1)
                        .windowHours(24)
                        .build())
                .promotionFacts(new java.util.ArrayList<>(List.of(
                        PromotionFact.builder()
                                .type("FULL_REDUCTION")
                                .threshold(new BigDecimal("300"))
                                .discount(new BigDecimal("30"))
                                .validUntil("2026-08-05")
                                .description("满300减30")
                                .build())))
                .build();
    }

    @Test
    @DisplayName("valid DSL with all fields passes")
    void validDslPasses() {
        DslValidationResult result = validator.validate(validDsl());
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("DSL missing promotionFacts → NEEDS_CONFIRMATION")
    void missingPromotionFacts() {
        CampaignDsl dsl = validDsl();
        dsl.setPromotionFacts(null);
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.isNeedsConfirmation()).isTrue();
        assertThat(result.getMissingFields()).contains("promotionFacts");
    }

    @Test
    @DisplayName("DSL with unknown field → INVALID")
    void unknownField() {
        CampaignDsl dsl = validDsl();
        dsl.getAudience().getConditions().add(
                AudienceCondition.builder()
                        .field("nonExistentField")
                        .operator("GT")
                        .valueType("INTEGER")
                        .value(1)
                        .build());
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("not in registry"));
    }

    @Test
    @DisplayName("DSL with wrong operator for field → INVALID")
    void wrongOperator() {
        CampaignDsl dsl = validDsl();
        // HIGH_VALUE tag only allows EQ
        dsl.getAudience().getConditions().add(
                AudienceCondition.builder()
                        .field("HIGH_VALUE")
                        .operator("GT")
                        .valueType("BOOLEAN")
                        .value(true)
                        .build());
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("not allowed for field"));
    }

    @Test
    @DisplayName("DSL with wrong valueType → INVALID")
    void wrongValueType() {
        CampaignDsl dsl = validDsl();
        dsl.getAudience().getConditions().get(0).setValueType("DECIMAL"); // activeDays7d is INTEGER
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("does not match field type"));
    }

    @Test
    @DisplayName("DSL with value below minimum → INVALID")
    void valueBelowMin() {
        CampaignDsl dsl = validDsl();
        dsl.getAudience().getConditions().get(0).setValue(-1); // activeDays7d min is 0
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("below min"));
    }

    @Test
    @DisplayName("activeDays7d exceeding 7 → INVALID (business rule)")
    void activeDaysExceedsSeven() {
        CampaignDsl dsl = validDsl();
        dsl.getAudience().getConditions().get(0).setValue(8); // activeDays7d max is 7
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("activeDays7d"));
    }

    @Test
    @DisplayName("DSL with past sendAt → INVALID")
    void pastSendAt() {
        CampaignDsl dsl = validDsl();
        dsl.getSchedule().setSendAt(OffsetDateTime.now().minusDays(1).toString());
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("must be in the future"));
    }

    @Test
    @DisplayName("DSL with invalid timezone → INVALID")
    void invalidTimezone() {
        CampaignDsl dsl = validDsl();
        dsl.getSchedule().setTimezone("Not/A_Real_Zone");
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("not a valid ZoneId"));
    }

    @Test
    @DisplayName("DSL with invalid frequencyCap (maxTimes=0) → INVALID")
    void invalidFrequencyCap() {
        CampaignDsl dsl = validDsl();
        dsl.getFrequencyCap().setMaxTimes(0);
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("maxTimes must be > 0"));
    }

    @Test
    @DisplayName("DSL with more than 10 conditions → INVALID")
    void tooManyConditions() {
        CampaignDsl dsl = validDsl();
        for (int i = 0; i < 11; i++) {
            dsl.getAudience().getConditions().add(
                    AudienceCondition.builder()
                            .field("activeDays7d")
                            .operator("GTE")
                            .valueType("INTEGER")
                            .value(1)
                            .build());
        }
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("exceeds max"));
    }

    @Test
    @DisplayName("DSL with invalid objective → INVALID")
    void invalidObjective() {
        CampaignDsl dsl = validDsl();
        dsl.setObjective("INVALID");
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("objective must be one of"));
    }

    @Test
    @DisplayName("DSL with invalid channel → INVALID")
    void invalidChannel() {
        CampaignDsl dsl = validDsl();
        dsl.setChannel("SMS");
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("channel must be one of"));
    }

    @Test
    @DisplayName("DSL with blank campaignName → INVALID")
    void blankName() {
        CampaignDsl dsl = validDsl();
        dsl.setCampaignName("");
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("campaignName is required"));
    }

    @Test
    @DisplayName("DSL with null → INVALID")
    void nullDsl() {
        DslValidationResult result = validator.validate(null);
        assertThat(result.getErrors()).contains("DSL is null");
    }

    @Test
    @DisplayName("TAG field with boolean true value passes")
    void tagFieldBooleanTrue() {
        CampaignDsl dsl = validDsl();
        dsl.getAudience().getConditions().add(
                AudienceCondition.builder()
                        .field("HIGH_VALUE")
                        .operator("EQ")
                        .valueType("BOOLEAN")
                        .value(true)
                        .build());
        DslValidationResult result = validator.validate(dsl);
        assertThat(result.isValid()).isTrue();
    }
}
