package com.pulseflow.boot.agenttools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.campaign.draft.CampaignDraftMapper;
import com.pulseflow.campaign.draft.CampaignDraftService;
import com.pulseflow.campaign.dsl.*;
import com.pulseflow.campaign.exception.CampaignConflictException;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.campaign.preview.AudiencePreviewResult;
import com.pulseflow.campaign.preview.AudiencePreviewService;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, authenticated Proposal-to-Draft business transition. */
@Service
@RequiredArgsConstructor
public class CampaignProposalDraftService {
    private final CampaignDraftMapper draftMapper;
    private final CampaignDraftService drafts;
    private final AgentInvestigationOwnership ownership;
    private final AgentProposalClient proposals;
    private final CampaignDslValidator validator;
    private final AudiencePreviewService preview;

    public record Proposal(String campaignName, String objective, String rationale,
            AudienceGroup targetAudience, String channel, CampaignSchedule schedule,
            FrequencyCap frequencyCap, List<PromotionFact> promotionFacts,
            List<String> supportingEvidenceIds) {}

    @Transactional
    public CampaignDraft create(String proposalId, Long operatorId) {
        AgentInvestigationOwnership.validId(proposalId);
        if (operatorId == null || operatorId <= 0) throw new CampaignForbiddenException("authentication_required");

        // The Java draft is authoritative after commit, even if Agent status repair is unavailable.
        CampaignDraft existing = find(proposalId);
        if (existing != null) return ownedDraft(existing, operatorId);

        JsonNode snapshot = proposals.read(proposalId);
        JsonNode record = snapshot.path("record");
        String investigationId = record.path("investigationId").asText("");
        AgentInvestigationOwnership.validId(investigationId);
        ownership.lockOwner(investigationId, operatorId);
        if (!proposalId.equals(record.path("id").asText())
                || record.path("ownerId").asLong(-1) != operatorId)
            throw new CampaignForbiddenException("proposal_forbidden");

        // Recheck under the owner-row lock, serializing concurrent requests for this investigation.
        existing = find(proposalId);
        if (existing != null) return ownedDraft(existing, operatorId);
        if (!"GENERATED".equals(record.path("status").asText()))
            throw new CampaignConflictException("proposal_not_generated");

        Proposal proposal;
        try { proposal = JsonUtil.fromJson(record.path("proposal").toString(), Proposal.class); }
        catch (RuntimeException error) { throw new IllegalArgumentException("invalid_proposal"); }
        if (proposal.rationale() == null || proposal.rationale().isBlank()
                || proposal.supportingEvidenceIds() == null || proposal.supportingEvidenceIds().isEmpty()
                || proposal.supportingEvidenceIds().size() > 20) throw new IllegalArgumentException("invalid_proposal");
        Set<String> evidenceIds = new HashSet<>();
        snapshot.path("evidenceIds").forEach(id -> evidenceIds.add(id.asText()));
        for (String id : proposal.supportingEvidenceIds()) {
            AgentInvestigationOwnership.validId(id);
            if (!evidenceIds.contains(id)) throw new IllegalArgumentException("invalid_proposal_evidence");
        }
        if (new HashSet<>(proposal.supportingEvidenceIds()).size() != proposal.supportingEvidenceIds().size())
            throw new IllegalArgumentException("duplicate_proposal_evidence");

        // Promotion facts come exclusively from the stored proposal. The endpoint accepts no body.
        CampaignDsl dsl = CampaignDsl.builder().schemaVersion(1).campaignName(proposal.campaignName())
                .objective(proposal.objective()).audience(proposal.targetAudience())
                .channel(proposal.channel()).schedule(proposal.schedule())
                .frequencyCap(proposal.frequencyCap()).promotionFacts(proposal.promotionFacts()).build();
        DslValidationResult checked = validator.validate(dsl);
        if (!checked.getErrors().isEmpty()) throw new IllegalArgumentException("invalid_campaign_dsl");
        AudiencePreviewResult audience = preview.preview(dsl);
        if (audience == null || (audience.getWarnings() != null && audience.getWarnings().stream()
                .anyMatch(warning -> warning != null && warning.startsWith("preview failed:"))))
            throw new AgentGatewayException(503, "audience_preview_unavailable");
        CampaignDraft draft = drafts.createDraft(proposalId, operatorId, "", dsl, checked, audience);
        draft.setInvestigationId(investigationId);
        if (draftMapper.updateById(draft) != 1)
            throw new IllegalStateException("draft_investigation_link_failed");
        return draft;
    }

    private CampaignDraft find(String proposalId) {
        return draftMapper.selectOne(new LambdaQueryWrapper<CampaignDraft>()
                .eq(CampaignDraft::getRequestId, proposalId));
    }

    private CampaignDraft ownedDraft(CampaignDraft draft, Long operatorId) {
        if (draft.getInvestigationId() == null) throw new CampaignConflictException("legacy_draft_collision");
        ownership.assertOwner(draft.getInvestigationId(), operatorId);
        if (!operatorId.equals(draft.getOperatorId()))
            throw new CampaignForbiddenException("draft_forbidden");
        return drafts.loadDraft(draft.getId(), operatorId);
    }
}
