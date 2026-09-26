package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.boot.config.GlobalExceptionHandler;
import com.pulseflow.campaign.draft.*;
import com.pulseflow.campaign.dsl.*;
import com.pulseflow.campaign.exception.CampaignConflictException;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.campaign.preview.*;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import com.pulseflow.common.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentDraftServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final CampaignDraftService drafts = mock(CampaignDraftService.class);
    final CampaignDslValidator validator = mock(CampaignDslValidator.class);
    final AudiencePreviewService preview = mock(AudiencePreviewService.class);
    final AgentDraftService service = new AgentDraftService(jdbc, drafts, validator, preview);
    final String investigation = UUID.randomUUID().toString();
    final String grantId = UUID.randomUUID().toString();
    final String token = grantId + ".test-private-capability";

    static AgentDraftService.Proposal proposal(String evidenceId) {
        return new AgentDraftService.Proposal("Recall draft", "RETENTION", "Supported by aggregate facts",
                AudienceGroup.builder().logic("AND").conditions(List.of(AudienceCondition.builder()
                        .field("activeDays7d").operator("GTE").value(5).valueType("INTEGER").build())).build(),
                "PUSH", CampaignSchedule.builder().type("ONCE")
                        .sendAt(OffsetDateTime.now().plusDays(2).toString()).timezone("Asia/Shanghai").build(),
                FrequencyCap.builder().maxTimes(1).windowHours(24).build(), List.of(), List.of(evidenceId));
    }

    Map<String, Object> grant() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", grantId); row.put("token_hash", AgentDraftService.hash(token));
        row.put("investigation_id", investigation); row.put("operator_id", 1024L);
        row.put("expires_at", Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
        row.put("authorized_facts_json", "[]");
        return row;
    }
    void stored(Map<String, Object> row) {
        when(jdbc.queryForList(anyString(), eq(grantId))).thenReturn(List.of(row));
    }
    CampaignDraft draft() {
        return CampaignDraft.builder().id(77L).operatorId(1024L).validationStatus("VALIDATED")
                .estimatedAudienceCount(42L).profileDataVersion("profile-v1").build();
    }

    @Test void onlyDraftIsCreatedAfterJavaValidationAndPreview() {
        Map<String, Object> row = grant();
        row.put("expires_at", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        stored(row);
        var checked = DslValidationResult.ok(List.of());
        var audience = AudiencePreviewResult.builder().estimatedCount(42).dataVersion("profile-v1").build();
        when(validator.validate(any())).thenReturn(checked);
        when(preview.preview(any())).thenReturn(audience);
        when(drafts.createDraft(eq(grantId), eq(1024L), isNull(), any(), eq(checked), eq(audience)))
                .thenReturn(draft());
        var response = service.create(token, new AgentDraftService.Request(investigation,
                proposal(UUID.randomUUID().toString())));
        assertThat(response.state()).isEqualTo("DRAFT");
        assertThat(response.requiresHumanConfirmation()).isTrue();
        assertThat(response.approvalLevel()).isEqualTo("PROPOSE");
        verify(validator).validate(any(CampaignDsl.class));
        verify(preview).preview(any(CampaignDsl.class));
        verify(drafts).createDraft(eq(grantId), eq(1024L), isNull(), any(), eq(checked), eq(audience));
        verify(drafts, never()).confirmAndCreate(anyLong(), anyLong());
    }

    @Test void missingWrongExpiredAndScopeMismatchedGrantsCannotWrite() {
        var request = new AgentDraftService.Request(investigation, proposal(UUID.randomUUID().toString()));
        assertThatThrownBy(() -> service.create(null, request)).isInstanceOf(CampaignForbiddenException.class);
        stored(grant());
        assertThatThrownBy(() -> service.create(grantId + ".wrong", request))
                .isInstanceOf(CampaignForbiddenException.class);
        assertThatThrownBy(() -> service.create(token, new AgentDraftService.Request(
                UUID.randomUUID().toString(), request.proposal()))).isInstanceOf(CampaignForbiddenException.class);
        Map<String, Object> expired = grant();
        expired.put("expires_at", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        stored(expired);
        assertThatThrownBy(() -> service.create(token, request)).isInstanceOf(CampaignForbiddenException.class);
        verifyNoInteractions(drafts, validator, preview);
    }

    @Test void grantReplayIsIdempotentAndCannotReplaceItsProposal() {
        var proposal = proposal(UUID.randomUUID().toString());
        Map<String, Object> row = grant();
        row.put("draft_id", 77L); row.put("proposal_hash", AgentDraftService.hash(JsonUtil.toJson(proposal)));
        stored(row);
        when(drafts.loadDraft(77L, 1024L)).thenReturn(draft());
        assertThat(service.create(token, new AgentDraftService.Request(investigation, proposal)).draftId())
                .isEqualTo(77L);
        assertThatThrownBy(() -> service.create(token, new AgentDraftService.Request(investigation,
                proposal(UUID.randomUUID().toString())))).isInstanceOf(CampaignConflictException.class);
        verify(drafts, never()).createDraft(anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test void invalidDslAndFailedPreviewDoNotPersistDrafts() {
        stored(grant());
        when(validator.validate(any())).thenReturn(DslValidationResult.invalid(List.of("invalid")));
        var request = new AgentDraftService.Request(investigation, proposal(UUID.randomUUID().toString()));
        assertThatThrownBy(() -> service.create(token, request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(drafts, preview);
        when(validator.validate(any())).thenReturn(DslValidationResult.ok(List.of()));
        when(preview.preview(any())).thenReturn(AudiencePreviewResult.builder()
                .warnings(List.of("preview failed: internal detail")).build());
        assertThatThrownBy(() -> service.create(token, request)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(drafts);
    }

    @Test void agentCannotInventPromotionFactsOutsideTheGrant() {
        stored(grant());
        var original = proposal(UUID.randomUUID().toString());
        var invented = new AgentDraftService.Proposal(original.campaignName(), original.objective(),
                original.rationale(), original.targetAudience(), original.channel(), original.schedule(),
                original.frequencyCap(), List.of(PromotionFact.builder().type("COUPON")
                    .discount(new java.math.BigDecimal("999")).build()), original.supportingEvidenceIds());
        assertThatThrownBy(() -> service.create(token, new AgentDraftService.Request(investigation, invented)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(drafts, preview, validator);
    }

    @Test void machineTokenAloneCannotCreateOrConfirmAnInternalDraft() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AgentDraftController(service))
                .addFilters(new AgentToolAuthFilter("machine-secret"))
                .setControllerAdvice(new AgentToolExceptionHandler()).build();
        String body = JsonUtil.toJson(new AgentDraftService.Request(investigation, proposal(UUID.randomUUID().toString())));
        mvc.perform(post("/internal/v1/agent-tools/campaign-drafts").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/agent-tools/campaign-drafts").contentType("application/json").content(body)
                .header("X-PulseFlow-Agent-Token", "machine-secret"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/agent-tools/campaign-drafts/77/confirm")
                .header("X-PulseFlow-Agent-Token", "machine-secret").header("X-PulseFlow-Draft-Grant", token))
                .andExpect(status().isNotFound());
        verifyNoInteractions(drafts, preview, validator);
    }

    @Test void grantIssueRequiresJavaRegisteredInvestigationOwner() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(investigation))).thenReturn(List.of(1024L));
        assertThatThrownBy(() -> service.issue(investigation, 2048L)).isInstanceOf(CampaignForbiddenException.class);
        String issued = service.issue(investigation, 1024L);
        assertThat(issued).contains(".");
        verify(jdbc).update(anyString(), anyString(), eq(AgentDraftService.hash(issued)),
                eq(investigation), eq(1024L), any(), any(), eq("[]"));
    }

    @Test void agentCredentialsCannotUseTheNormalUserConfirmEndpoint() throws Exception {
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginId).thenThrow(new RuntimeException("no user session"));
            var mvc = MockMvcBuilders.standaloneSetup(new CampaignDraftController(drafts))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(post("/api/campaign-drafts/77/confirm")
                            .header("X-PulseFlow-Agent-Token", "machine-secret")
                            .header("X-PulseFlow-Draft-Grant", token))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(drafts);
    }
}
