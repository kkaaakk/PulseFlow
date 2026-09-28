package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User action after the Agent has stopped at its persisted Proposal. */
@RestController
@RequestMapping("/api/campaign-proposals")
@RequiredArgsConstructor
public class CampaignProposalDraftController {
    private final CampaignProposalDraftService drafts;
    private final AgentProposalClient proposals;

    public record Response(Long draftId, String status, Long estimatedAudienceCount, String dataVersion) {}

    @PostMapping("/{proposalId}/draft")
    public ApiResponse<Response> create(@PathVariable String proposalId) {
        StpUtil.checkLogin();
        CampaignDraft draft = drafts.create(proposalId, StpUtil.getLoginIdAsLong());
        try { proposals.markDraftCreated(proposalId, draft.getId()); }
        catch (AgentGatewayException ignored) { /* retry repairs status; committed Java draft remains valid */ }
        return ApiResponse.success(new Response(draft.getId(), draft.getValidationStatus(),
                draft.getEstimatedAudienceCount(), draft.getProfileDataVersion()));
    }
}
