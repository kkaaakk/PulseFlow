package com.pulseflow.campaign.draft;

import com.pulseflow.campaign.draft.CampaignDraftService;
import com.pulseflow.campaign.preview.AudiencePreviewResult;
import com.pulseflow.campaign.preview.AudiencePreviewService;
import com.pulseflow.campaign.dsl.AudienceCondition;
import com.pulseflow.campaign.dsl.AudienceGroup;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.dsl.CampaignSchedule;
import com.pulseflow.campaign.dsl.FrequencyCap;
import com.pulseflow.campaign.dsl.PromotionFact;
import com.pulseflow.campaign.validation.CampaignFieldRegistry;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import com.pulseflow.campaign.validation.DslToRuleConverter;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.campaign.draft.CampaignDraftMapper;
import com.pulseflow.campaign.exception.CampaignConflictException;
import com.pulseflow.campaign.exception.CampaignResourceNotFoundException;
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
 * {@link CampaignDraftService}; only the Mappers are mocked.</p>
 */
class CampaignDraftServiceTest {

    private static CampaignFieldRegistry registry;
    private static CampaignDslValidator validator;
    private static DslToRuleConverter converter;

    private CampaignDraftMapper draftMapper;
    private CampaignMapper campaignMapper;
    private CampaignRuleMapper ruleMapper;
    private AudiencePreviewService previewService;
    private CampaignDraftService draftService;

    /** In-memory draft store keyed by id. */
    private Map<Long, CampaignDraft> draftStore;
    private AtomicLong draftIdSeq;
    private AtomicLong campaignIdSeq;

    @BeforeAll
    static void initRegistry() {
        registry = new CampaignFieldRegistry();
        registry.init();
        validator = new CampaignDslValidator(registry);
        converter = new DslToRuleConverter(registry);
    }

    @BeforeEach
    void setUp() {
        draftMapper = mock(CampaignDraftMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        ruleMapper = mock(CampaignRuleMapper.class);
        previewService = mock(AudiencePreviewService.class);

        draftService = new CampaignDraftService(
                draftMapper, campaignMapper, ruleMapper,
                validator, converter);
        ReflectionTestUtils.setField(draftService, "audiencePreviewService", previewService);
        ReflectionTestUtils.setField(draftService, "draftTtlHours", 24);

        draftStore = new ConcurrentHashMap<>();
        draftIdSeq = new AtomicLong(100);
        campaignIdSeq = new AtomicLong(200);

        // Mock draftMapper: selectById reads from store; insert/updateById write to it
        when(draftMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return draftStore.get(id);
        });
        when(draftMapper.insert(any())).thenAnswer(inv -> {
            CampaignDraft d = inv.getArgument(0);
            d.setId(draftIdSeq.incrementAndGet());
            draftStore.put(d.getId(), d);
            return 1;
        });
        when(draftMapper.updateById(any())).thenAnswer(inv -> {
            CampaignDraft d = inv.getArgument(0);
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
        CampaignDraft draft = draftService.createDraft(
                "req-refresh", 1024L, "refresh audience", dsl,
                validator.validate(dsl), AudiencePreviewResult.builder()
                        .estimatedCount(2).dataVersion("old-version").calculationMode("SNAPSHOT").build());
        when(previewService.preview(any(CampaignDsl.class))).thenReturn(
                AudiencePreviewResult.builder().estimatedCount(9)
                        .dataVersion("new-version").calculationMode("SNAPSHOT").build());

        CampaignDraftService.DraftUpdateResult refreshed =
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

    private CampaignDraft persistValidatedDraft() {
        CampaignDsl dsl = validDsl();
        CampaignDraft draft = CampaignDraft.builder()
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
        CampaignDraft draft = persistValidatedDraft();

        CampaignDraftService.ConfirmResult result =
                draftService.confirmAndCreate(draft.getId(), 1L);

        assertThat(result.campaignId()).isNotNull().isGreaterThan(0);
        assertThat(result.draftId()).isEqualTo(draft.getId());
        assertThat(result.idempotent()).isFalse();

        // Campaign was inserted via the real CampaignMapper (not bypassed)
        verify(campaignMapper, times(1)).insert(any(Campaign.class));
        // Rules were inserted (2 conditions → 2 rules)
        verify(ruleMapper, atLeast(2)).insert(any(CampaignRule.class));

        // Draft marked CONFIRMED with campaign id
        CampaignDraft updated = draftStore.get(draft.getId());
        assertThat(updated.getValidationStatus()).isEqualTo("CONFIRMED");
        assertThat(updated.getConfirmedCampaignId()).isEqualTo(result.campaignId());
        assertThat(updated.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("non-VALIDATED draft cannot be confirmed (409)")
    void nonValidatedDraftCannotBeConfirmed() {
        CampaignDraft draft = persistValidatedDraft();
        draft.setValidationStatus("NEEDS_CONFIRMATION");
        draftStore.put(draft.getId(), draft);

        assertThatThrownBy(() -> draftService.confirmAndCreate(draft.getId(), 1L))
                .isInstanceOf(CampaignConflictException.class)
                .hasMessageContaining("not VALIDATED");

        // No campaign created
        verify(campaignMapper, never()).insert(any());
    }

    @Test
    @DisplayName("expired draft cannot be confirmed (409)")
    void expiredDraftCannotBeConfirmed() {
        CampaignDraft draft = persistValidatedDraft();
        draft.setExpiresAt(LocalDateTime.now().minusHours(1));
        draftStore.put(draft.getId(), draft);

        assertThatThrownBy(() -> draftService.confirmAndCreate(draft.getId(), 1L))
                .isInstanceOf(CampaignConflictException.class)
                .hasMessageContaining("expired");

        verify(campaignMapper, never()).insert(any());
    }

    @Test
    @DisplayName("missing draft returns 404")
    void missingDraftReturns404() {
        assertThatThrownBy(() -> draftService.confirmAndCreate(99999L, 1L))
                .isInstanceOf(CampaignResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("re-confirm is idempotent")
    void reConfirmIsIdempotent() {
        CampaignDraft draft = persistValidatedDraft();
        Long draftId = draft.getId();

        CampaignDraftService.ConfirmResult first = draftService.confirmAndCreate(draftId, 1L);
        CampaignDraftService.ConfirmResult second = draftService.confirmAndCreate(draftId, 1L);

        assertThat(second.idempotent()).isTrue();
        assertThat(second.campaignId()).isEqualTo(first.campaignId());
        // Only one campaign created across both calls
        verify(campaignMapper, times(1)).insert(any(Campaign.class));
    }

    @Test
    @DisplayName("confirmed campaign description embeds objective for later review extraction")
    void confirmedCampaignDescriptionHasObjective() {
        CampaignDraft draft = persistValidatedDraft();

        draftService.confirmAndCreate(draft.getId(), 1L);

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignMapper).insert(captor.capture());
        Campaign created = captor.getValue();
        assertThat(created.getDescription()).startsWith("[OBJ:CONVERSION]");
        assertThat(created.getStatus()).isEqualTo("DRAFT");
        assertThat(created.getChannel()).isEqualTo("IN_APP");
    }
}
