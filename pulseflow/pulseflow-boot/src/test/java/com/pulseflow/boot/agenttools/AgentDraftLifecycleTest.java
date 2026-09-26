package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.draft.*;
import com.pulseflow.campaign.dsl.PromotionFact;
import com.pulseflow.campaign.preview.*;
import com.pulseflow.campaign.validation.*;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentDraftLifecycleTest {
    @Test void agentStopsAtDraftAndOnlyNormalHumanConfirmCreatesCampaignAndRules() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CampaignDraftMapper draftMapper = mock(CampaignDraftMapper.class);
        CampaignMapper campaigns = mock(CampaignMapper.class);
        CampaignRuleMapper rules = mock(CampaignRuleMapper.class);
        var registry = new CampaignFieldRegistry();
        registry.init();
        var validator = new CampaignDslValidator(registry);
        var draftService = new CampaignDraftService(draftMapper, campaigns, rules, validator,
                new DslToRuleConverter(registry));
        AudiencePreviewService preview = mock(AudiencePreviewService.class);
        when(preview.preview(any())).thenReturn(AudiencePreviewResult.builder()
                .estimatedCount(42).dataVersion("profile-v1").build());
        AgentDraftService service = new AgentDraftService(jdbc, draftService, validator, preview);
        String grantId = UUID.randomUUID().toString();
        String token = grantId + ".private-test-grant";
        String investigation = UUID.randomUUID().toString();
        var base = AgentDraftServiceTest.proposal(UUID.randomUUID().toString());
        List<PromotionFact> facts = List.of(PromotionFact.builder().type("COUPON")
                .threshold(new BigDecimal("100")).discount(new BigDecimal("10")).build());
        var proposal = new AgentDraftService.Proposal(base.campaignName(), base.objective(), base.rationale(),
                base.targetAudience(), base.channel(), base.schedule(), base.frequencyCap(), facts,
                base.supportingEvidenceIds());
        var grant = new HashMap<String, Object>();
        grant.put("id", grantId); grant.put("token_hash", AgentDraftService.hash(token));
        grant.put("investigation_id", investigation); grant.put("operator_id", 1024L);
        grant.put("expires_at", Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
        grant.put("authorized_facts_json", JsonUtil.toJson(facts));
        when(jdbc.queryForList(anyString(), eq(grantId))).thenReturn(List.of(grant));
        AtomicReference<CampaignDraft> saved = new AtomicReference<>();
        when(draftMapper.insert(any(CampaignDraft.class))).thenAnswer(call -> {
            CampaignDraft draft = call.getArgument(0); draft.setId(77L); saved.set(draft); return 1;
        });
        when(draftMapper.selectById(77L)).thenAnswer(ignored -> saved.get());
        var result = service.create(token, new AgentDraftService.Request(investigation, proposal));
        assertThat(result.state()).isEqualTo("DRAFT");
        assertThat(result.validationStatus()).isEqualTo("VALIDATED");
        assertThat(result.estimatedCount()).isEqualTo(42);
        verifyNoInteractions(campaigns, rules);

        when(campaigns.insert(any(Campaign.class))).thenAnswer(call -> {
            Campaign campaign = call.getArgument(0); campaign.setId(99L);
            assertThat(campaign.getStatus()).isEqualTo("DRAFT"); return 1;
        });
        var confirmed = draftService.confirmAndCreate(77L, 1024L);
        assertThat(confirmed.campaignId()).isEqualTo(99L);
        verify(campaigns, times(1)).insert(any(Campaign.class));
        verify(rules, atLeastOnce()).insert(any(com.pulseflow.entity.CampaignRule.class));
    }
}
