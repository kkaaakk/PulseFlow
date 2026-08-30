package com.pulseflow.ai.integration;

import com.pulseflow.ai.application.CampaignAiDraftService;
import com.pulseflow.ai.application.AudiencePreviewResult;
import com.pulseflow.ai.application.AudiencePreviewService;
import com.pulseflow.ai.domain.campaign.AudienceCondition;
import com.pulseflow.ai.domain.campaign.AudienceGroup;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.CampaignSchedule;
import com.pulseflow.ai.domain.campaign.FrequencyCap;
import com.pulseflow.ai.domain.campaign.PromotionFact;
import com.pulseflow.ai.guardrail.AiFieldRegistry;
import com.pulseflow.ai.guardrail.CampaignDslValidator;
import com.pulseflow.ai.guardrail.DslToRuleConverter;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiDraft;
import com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiDraftMapper;
import com.pulseflow.ai.support.AiConflictException;
import com.pulseflow.ai.support.AiResourceNotFoundException;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignRule;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test for the Campaign Creation flow.
 *
 * <p>Verifies the architectural invariants (design §11.5):</p>
 * <ul>
 *   <li>Non-VALIDATED drafts cannot be confirmed (409).</li>
 *   <li>Expired drafts cannot be confirmed (409).</li>
 *   <li>Missing draft returns 404.</li>
 *   <li>A VALIDATED draft, when confirmed, inserts a real Campaign + rules
 *       via the original CampaignMapper — AI never bypasses this.</li>
 *   <li>Re-confirm is idempotent.</li>
 * </ul>
 *
 * <p>Uses real {@link CampaignDslValidator}/{@link DslToRuleConverter}/
 * {@link CampaignAiDraftService}; only the Mappers are mocked.</p>
 */
class CampaignCreationFlowTest {

    private static AiFieldRegistry registry;
    private static CampaignDslValidator validator;
    private static DslToRuleConverter converter;

    private CampaignAiDraftMapper draftMapper;
    private CampaignMapper campaignMapper;
    private CampaignRuleMapper ruleMapper;
    private AiFeatureProperties properties;
    private AudiencePreviewService previewService;
    private CampaignAiDraftService draftService;

    /** In-memory draft store keyed by id. */
    private Map<Long, CampaignAiDraft> draftStore;
    private AtomicLong draftIdSeq;
    private AtomicLong campaignIdSeq;

    @BeforeAll
    static void initRegistry() {
        registry = new AiFieldRegistry();
        registry.init();
        validator = new CampaignDslValidator(registry);
        converter = new DslToRuleConverter(registry);
    }

    @BeforeEach
    void setUp() {
        draftMapper = mock(CampaignAiDraftMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        ruleMapper = mock(CampaignRuleMapper.class);
        properties = new AiFeatureProperties();
        properties.setDraftTtlHours(24);
        previewService = mock(AudiencePreviewService.class);

        draftService = new CampaignAiDraftService(
                draftMapper, campaignMapper, ruleMapper,
                validator, converter, properties);
        ReflectionTestUtils.setField(draftService, "audiencePreviewService", previewService);

        draftStore = new ConcurrentHashMap<>();
        draftIdSeq = new AtomicLong(100);
        campaignIdSeq = new AtomicLong(200);

        // Mock draftMapper: selectById reads from store; insert/updateById write to it
        when(draftMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return draftStore.get(id);
        });
        when(draftMapper.insert(any())).thenAnswer(inv -> {
            CampaignAiDraft d = inv.getArgument(0);
            d.setId(draftIdSeq.incrementAndGet());
            draftStore.put(d.getId(), d);
            return 1;
        });
        when(draftMapper.updateById(any())).thenAnswer(inv -> {
            CampaignAiDraft d = inv.getArgument(0);
            draftStore.put(d.getId(), d);
            return 1;
        });

        // Mock campaignMapper: insert assigns an id
        when(campaignMapper.insert(any())).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            c.setId(campaignIdSeq.incrementAndGet());
            return 1;
        });
        when(ruleMapper.insert(any())).thenReturn(1);
    }

    @Test
    @DisplayName("refresh preview 重新计算并持久化人群快照")
    void refreshPreviewRecomputesAudienceSnapshot() {
        CampaignDsl dsl = validDsl();
        CampaignAiDraft draft = draftService.saveGeneratedDraft(
                "req-refresh", 1024L, "refresh audience", dsl,
                validator.validate(dsl), AudiencePreviewResult.builder()
                        .estimatedCount(2).dataVersion("old-version").calculationMode("SNAPSHOT").build());
        when(previewService.preview(any(CampaignDsl.class))).thenReturn(
                AudiencePreviewResult.builder().estimatedCount(9)
                        .dataVersion("new-version").calculationMode("SNAPSHOT").build());

        CampaignAiDraftService.DraftUpdateResult refreshed =
                draftService.refreshPreview(draft.getId(), 1024L);

        assertThat(refreshed.draft().getEstimatedAudienceCount()).isEqualTo(9L);
        assertThat(refreshed.draft().getProfileDataVersion()).isEqualTo("new-version");
        verify(previewService).preview(any(CampaignDsl.class));
    }

    private CampaignDsl validDsl() {
        return CampaignDsl.builder()
                .schemaVersion(1)
                .campaignName("夏末满减活动")
                .objective("CONVERSION")
                .audience(AudienceGroup.builder()
                        .logic("AND")
                        .conditions(new ArrayList<>(List.of(
                                AudienceCondition.builder()
                                        .field("activeDays7d")
                                        .operator("GTE")
                                        .valueType("INTEGER")
                                        .value(5)
                                        .build(),
                                AudienceCondition.builder()
                                        .field("HIGH_VALUE")
                                        .operator("EQ")
                                        .valueType("BOOLEAN")
                                        .value(true)
                                        .build())))
                        .build())
                .channel("IN_APP")
                .schedule(CampaignSchedule.builder()
                        .type("ONCE")
                        .sendAt(OffsetDateTime.now().plusDays(2).toString())
                        .timezone("Asia/Shanghai")
                        .build())
                .frequencyCap(FrequencyCap.builder()
                        .maxTimes(1)
                        .windowHours(24)
                        .build())
                .promotionFacts(new ArrayList<>(List.of(
                        PromotionFact.builder()
                                .type("FULL_REDUCTION")
                                .threshold(new BigDecimal("300"))
                                .discount(new BigDecimal("30"))
                                .validUntil("2026-08-05")
                                .description("满300减30")
                                .build())))
                .build();
    }

    private CampaignAiDraft persistValidatedDraft() {
        CampaignDsl dsl = validDsl();
        CampaignAiDraft draft = CampaignAiDraft.builder()
                .id(draftIdSeq.incrementAndGet())
                .requestId("req-test-1")
                .operatorId(1L)
                .sourceText("给高价值活跃用户推满减")
                .schemaVersion(1)
                .dslJson(JsonUtil.toJson(dsl))
                .validationStatus("VALIDATED")
                .warningsJson("[]")
                .estimatedAudienceCount(18420L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
        draftStore.put(draft.getId(), draft);
        return draft;
    }

    @Test
    @DisplayName("VALIDATED draft → confirm → real Campaign + rules created via CampaignMapper")
    void confirmValidatedDraftCreatesCampaign() {
        CampaignAiDraft draft = persistValidatedDraft();

        CampaignAiDraftService.ConfirmResult result =
                draftService.confirmAndCreate(draft.getId(), 1L);

        assertThat(result.campaignId()).isNotNull().isGreaterThan(0);
        assertThat(result.draftId()).isEqualTo(draft.getId());
        assertThat(result.idempotent()).isFalse();

        // Campaign was inserted via the real CampaignMapper (not bypassed)
        verify(campaignMapper, times(1)).insert(any(Campaign.class));
        // Rules were inserted (2 conditions → 2 rules)
        verify(ruleMapper, atLeast(2)).insert(any(CampaignRule.class));

        // Draft marked CONFIRMED with campaign id
        CampaignAiDraft updated = draftStore.get(draft.getId());
        assertThat(updated.getValidationStatus()).isEqualTo("CONFIRMED");
        assertThat(updated.getConfirmedCampaignId()).isEqualTo(result.campaignId());
        assertThat(updated.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("non-VALIDATED draft cannot be confirmed (409)")
    void nonValidatedDraftCannotBeConfirmed() {
        CampaignAiDraft draft = persistValidatedDraft();
        draft.setValidationStatus("NEEDS_CONFIRMATION");
        draftStore.put(draft.getId(), draft);

        assertThatThrownBy(() -> draftService.confirmAndCreate(draft.getId(), 1L))
                .isInstanceOf(AiConflictException.class)
                .hasMessageContaining("not VALIDATED");

        // No campaign created
        verify(campaignMapper, never()).insert(any());
    }

    @Test
    @DisplayName("expired draft cannot be confirmed (409)")
    void expiredDraftCannotBeConfirmed() {
        CampaignAiDraft draft = persistValidatedDraft();
        draft.setExpiresAt(LocalDateTime.now().minusHours(1));
        draftStore.put(draft.getId(), draft);

        assertThatThrownBy(() -> draftService.confirmAndCreate(draft.getId(), 1L))
                .isInstanceOf(AiConflictException.class)
                .hasMessageContaining("expired");

        verify(campaignMapper, never()).insert(any());
    }

    @Test
    @DisplayName("missing draft returns 404")
    void missingDraftReturns404() {
        assertThatThrownBy(() -> draftService.confirmAndCreate(99999L, 1L))
                .isInstanceOf(AiResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("re-confirm is idempotent")
    void reConfirmIsIdempotent() {
        CampaignAiDraft draft = persistValidatedDraft();
        Long draftId = draft.getId();

        CampaignAiDraftService.ConfirmResult first = draftService.confirmAndCreate(draftId, 1L);
        CampaignAiDraftService.ConfirmResult second = draftService.confirmAndCreate(draftId, 1L);

        assertThat(second.idempotent()).isTrue();
        assertThat(second.campaignId()).isEqualTo(first.campaignId());
        // Only one campaign created across both calls
        verify(campaignMapper, times(1)).insert(any(Campaign.class));
    }

    @Test
    @DisplayName("confirmed campaign description embeds objective for later review extraction")
    void confirmedCampaignDescriptionHasObjective() {
        CampaignAiDraft draft = persistValidatedDraft();

        draftService.confirmAndCreate(draft.getId(), 1L);

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignMapper).insert(captor.capture());
        Campaign created = captor.getValue();
        assertThat(created.getDescription()).startsWith("[OBJ:CONVERSION]");
        assertThat(created.getStatus()).isEqualTo("DRAFT");
        assertThat(created.getChannel()).isEqualTo("IN_APP");
    }
}
