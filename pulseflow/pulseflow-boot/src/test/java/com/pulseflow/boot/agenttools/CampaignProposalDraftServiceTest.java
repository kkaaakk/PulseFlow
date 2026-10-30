package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.campaign.draft.CampaignDraftMapper;
import com.pulseflow.campaign.draft.CampaignDraftService;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.dsl.DslValidationResult;
import com.pulseflow.campaign.exception.CampaignConflictException;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.campaign.preview.AudiencePreviewResult;
import com.pulseflow.campaign.preview.AudiencePreviewService;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import com.pulseflow.common.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CampaignProposalDraftServiceTest {
    final CampaignDraftMapper mapper = mock(CampaignDraftMapper.class);
    final CampaignDraftService drafts = mock(CampaignDraftService.class);
    final AgentInvestigationOwnership ownership = mock(AgentInvestigationOwnership.class);
    final AgentProposalClient proposals = mock(AgentProposalClient.class);
    final CampaignDslValidator validator = mock(CampaignDslValidator.class);
    final AudiencePreviewService preview = mock(AudiencePreviewService.class);
    final CampaignProposalDraftService service = new CampaignProposalDraftService(
            mapper, drafts, ownership, proposals, validator, preview);
    final String proposalId = UUID.randomUUID().toString();
    final String investigationId = UUID.randomUUID().toString();
    final String evidenceId = UUID.randomUUID().toString();

    /**
     * 构造具有固定所有者、调查和证据关联的 Agent 提案快照。
     */
    JsonNode snapshot(String status, long owner, String referencedEvidence, String knownEvidence) {
        String json = """
                {"record":{"id":"%s","investigationId":"%s","ownerId":%d,"status":"%s",
                  "proposal":{"campaignName":"Recall review","objective":"RETENTION",
                    "rationale":"Supported by aggregate evidence",
                    "targetAudience":{"logic":"AND","conditions":[{"field":"activeDays7d","operator":"GTE","value":5,"valueType":"INTEGER"}]},
                    "channel":"PUSH","schedule":{"type":"ONCE","sendAt":"2027-01-01T10:00:00+08:00","timezone":"Asia/Shanghai"},
                    "frequencyCap":{"maxTimes":1,"windowHours":24},
                    "promotionFacts":[{"type":"COUPON","discount":10,"description":"approved offer"}],
                    "supportingEvidenceIds":["%s"]}},"evidenceIds":["%s"]}
                """.formatted(proposalId, investigationId, owner, status, referencedEvidence, knownEvidence);
        return JsonUtil.fromJson(json, JsonNode.class);
    }

    /**
     * 构造成功人群预估替身，供草稿边界测试复用。
     */
    void validPreview() {
        when(mapper.updateById(any(CampaignDraft.class))).thenReturn(1);
        when(validator.validate(any(CampaignDsl.class))).thenReturn(DslValidationResult.ok(List.of()));
        when(preview.preview(any(CampaignDsl.class))).thenReturn(
                AudiencePreviewResult.builder().estimatedCount(42).dataVersion("v1").warnings(List.of()).build());
        when(drafts.createDraft(eq(proposalId), eq(1024L), eq(""), any(CampaignDsl.class),
                any(DslValidationResult.class), any(AudiencePreviewResult.class)))
                .thenReturn(CampaignDraft.builder().id(77L).requestId(proposalId).operatorId(1024L).build());
    }

    /**
     * 验证所有者只能幂等创建一次草稿，促销事实取自持久化提案。
     */
    @Test void ownerCanCreateOnceAndPromotionFactsComeFromStoredProposal() {
        when(proposals.read(proposalId)).thenReturn(snapshot("GENERATED", 1024, evidenceId, evidenceId));
        validPreview();
        CampaignDraft created = service.create(proposalId, 1024L);
        assertThat(created.getId()).isEqualTo(77L);
        assertThat(created.getInvestigationId()).isEqualTo(investigationId);
        verify(ownership).lockOwner(investigationId, 1024L);
        var dsl = org.mockito.ArgumentCaptor.forClass(CampaignDsl.class);
        verify(drafts).createDraft(eq(proposalId), eq(1024L), eq(""), dsl.capture(), any(), any());
        assertThat(dsl.getValue().getPromotionFacts()).hasSize(1);
        assertThat(dsl.getValue().getPromotionFacts().get(0).getDescription()).isEqualTo("approved offer");
        verify(mapper).updateById(created);
    }

    /**
     * 验证非所有者不能使用他人的提案创建草稿。
     */
    @Test void anotherUserCannotUseProposal() {
        when(proposals.read(proposalId)).thenReturn(snapshot("GENERATED", 1024, evidenceId, evidenceId));
        assertThatThrownBy(() -> service.create(proposalId, 2048L))
                .isInstanceOf(CampaignForbiddenException.class);
        verify(drafts, never()).createDraft(anyString(), anyLong(), anyString(), any(), any(), any());
    }

    /**
     * 验证调查所有权独立校验，不能仅凭提案字段绕过。
     */
    @Test void investigationOwnershipIsCheckedSeparately() {
        when(proposals.read(proposalId)).thenReturn(snapshot("GENERATED", 1024, evidenceId, evidenceId));
        doThrow(new CampaignForbiddenException("denied")).when(ownership).lockOwner(investigationId, 1024L);
        assertThatThrownBy(() -> service.create(proposalId, 1024L))
                .isInstanceOf(CampaignForbiddenException.class);
    }

    /**
     * 验证已取消或被替代提案不可再创建草稿。
     */
    @Test void cancelledOrSupersededProposalCannotCreateDraft() {
        for (String status : List.of("CANCELLED", "SUPERSEDED", "DRAFT_CREATED")) {
            when(proposals.read(proposalId)).thenReturn(snapshot(status, 1024, evidenceId, evidenceId));
            assertThatThrownBy(() -> service.create(proposalId, 1024L))
                    .isInstanceOf(CampaignConflictException.class);
        }
        verify(drafts, never()).createDraft(anyString(), anyLong(), anyString(), any(), any(), any());
    }

    /**
     * 验证跨调查或不属于提案范围的证据引用被拒绝。
     */
    @Test void crossInvestigationEvidenceIsRejected() {
        when(proposals.read(proposalId)).thenReturn(snapshot("GENERATED", 1024, evidenceId, UUID.randomUUID().toString()));
        assertThatThrownBy(() -> service.create(proposalId, 1024L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证 Agent 不可用时重复请求仍返回已提交 Java 草稿。
     */
    @Test void existingDraftSurvivesAgentOutageAndIsIdempotent() {
        CampaignDraft existing = CampaignDraft.builder().id(77L).requestId(proposalId)
                .investigationId(investigationId).operatorId(1024L).build();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(drafts.loadDraft(77L, 1024L)).thenReturn(existing);
        assertThat(service.create(proposalId, 1024L).getId()).isEqualTo(77L);
        assertThat(service.create(proposalId, 1024L).getId()).isEqualTo(77L);
        verifyNoInteractions(proposals);
        verify(drafts, never()).createDraft(anyString(), anyLong(), anyString(), any(), any(), any());
    }

    /**
     * 验证依赖故障时也不会向其他操作员返回既有草稿。
     */
    @Test void existingDraftNeverLeaksToAnotherUserDuringAgentOutage() {
        CampaignDraft existing = CampaignDraft.builder().id(77L).requestId(proposalId)
                .investigationId(investigationId).operatorId(1024L).build();
        when(mapper.selectOne(any())).thenReturn(existing);
        assertThatThrownBy(() -> service.create(proposalId, 2048L))
                .isInstanceOf(CampaignForbiddenException.class);
        verifyNoInteractions(proposals);
    }

    /**
     * 验证非法提案 DSL 被共享校验器拒绝。
     */
    @Test void invalidDslIsRejectedBySharedValidator() {
        when(proposals.read(proposalId)).thenReturn(snapshot("GENERATED", 1024, evidenceId, evidenceId));
        when(validator.validate(any())).thenReturn(DslValidationResult.invalid(List.of("unsupported audience")));
        assertThatThrownBy(() -> service.create(proposalId, 1024L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(preview);
    }

    /**
     * 验证提案状态回写失败不影响已提交草稿，重复请求会再次尝试修复。
     */
    @Test void controllerReturnsCommittedDraftWhenStatusRepairFailsAndRetriesRepair() {
        CampaignProposalDraftService draftService = mock(CampaignProposalDraftService.class);
        CampaignDraft existing = CampaignDraft.builder().id(77L).operatorId(1024L)
                .validationStatus("VALIDATED").estimatedAudienceCount(42L).build();
        when(draftService.create(proposalId, 1024L)).thenReturn(existing);
        doThrow(new AgentGatewayException(503, "down")).doNothing()
                .when(proposals).markDraftCreated(proposalId, 77L);
        CampaignProposalDraftController controller = new CampaignProposalDraftController(draftService, proposals);
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(1024L);
            assertThat(controller.create(proposalId).getData().draftId()).isEqualTo(77L);
            assertThat(controller.create(proposalId).getData().draftId()).isEqualTo(77L);
        }
        verify(proposals, times(2)).markDraftCreated(proposalId, 77L);
    }

    /**
     * 验证外部提案访问映射为 403，浏览器不能指定可信操作员身份。
     */
    @Test void userApiMapsForeignProposalTo403AndNeverAcceptsBrowserOperator() throws Exception {
        CampaignProposalDraftService draftService = mock(CampaignProposalDraftService.class);
        doThrow(new CampaignForbiddenException("foreign_proposal"))
                .when(draftService).create(proposalId, 2048L);
        var mvc = MockMvcBuilders.standaloneSetup(
                new CampaignProposalDraftController(draftService, proposals))
                .setControllerAdvice(new AgentToolExceptionHandler()).build();
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(2048L);
            mvc.perform(post("/api/campaign-proposals/{id}/draft", proposalId)
                    .contentType("application/json")
                    .content("{\"operatorId\":1024,\"promotionFacts\":[{\"discount\":99}]}"))
                    .andExpect(status().isForbidden());
        }
        verify(draftService).create(proposalId, 2048L);
        verifyNoInteractions(proposals);
    }

    /**
     * 验证重复 POST 返回同一草稿，保持接口幂等。
     */
    @Test void userApiReturnsExistingDraftOnRepeatedPost() throws Exception {
        CampaignProposalDraftService draftService = mock(CampaignProposalDraftService.class);
        CampaignDraft existing = CampaignDraft.builder().id(77L).operatorId(1024L)
                .validationStatus("VALIDATED").estimatedAudienceCount(42L).build();
        when(draftService.create(proposalId, 1024L)).thenReturn(existing);
        var mvc = MockMvcBuilders.standaloneSetup(
                new CampaignProposalDraftController(draftService, proposals))
                .setControllerAdvice(new AgentToolExceptionHandler()).build();
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(1024L);
            for (int i = 0; i < 2; i++) {
                String response = mvc.perform(post("/api/campaign-proposals/{id}/draft", proposalId))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(response).contains("\"draftId\":77");
            }
        }
        verify(draftService, times(2)).create(proposalId, 1024L);
        verify(proposals, times(2)).markDraftCreated(proposalId, 77L);
    }
}
