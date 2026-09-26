package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.dsl.PromotionFact;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.common.util.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.List;

/** The Java login session, not a browser-supplied operatorId, owns every proposed draft. */
@RestController
@RequestMapping("/api/investigations")
public class AgentProposalGateway {
    private final AgentDraftService drafts;
    private final String agentUrl;
    private final String internalToken;
    private final RestClient client;

    public AgentProposalGateway(AgentDraftService drafts, RestClient.Builder builder,
            @Value("${pulseflow.agent.service-url:}") String agentUrl,
            @Value("${pulseflow.agent.internal-token:}") String internalToken) {
        this.drafts = drafts;
        this.agentUrl = agentUrl;
        this.internalToken = internalToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(120000);
        this.client = builder.requestFactory(factory).build();
    }

    public record Request(String question) {}
    public record ProposalRequest(String question, List<PromotionFact> promotionFacts) {}
    public record Response(String investigationId, Long draftId, String state,
                           String validationStatus, CampaignDsl dsl, Long estimatedCount,
                           String dataVersion, boolean requiresHumanConfirmation) {}

    @PostMapping
    public ApiResponse<JsonNode> investigate(@RequestBody Request body) {
        StpUtil.checkLogin();
        Long operator = StpUtil.getLoginIdAsLong();
        validate(body);
        JsonNode result;
        try {
            result = client.post().uri(agentUrl.replaceAll("/$", "") + "/internal/v1/investigations")
                    .header("X-PulseFlow-Agent-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("question", body.question())).retrieve().body(JsonNode.class);
        } catch (Exception ignored) { throw new IllegalStateException("agent_unavailable"); }
        if (result == null || !result.hasNonNull("id")) throw new IllegalStateException("agent_unavailable");
        String id = result.get("id").asText();
        AgentDraftService.validId(id);
        drafts.registerOwner(id, operator);
        return ApiResponse.success(result);
    }

    @PostMapping("/{investigationId}/proposal")
    public ApiResponse<Response> propose(@PathVariable String investigationId, @RequestBody ProposalRequest body) {
        StpUtil.checkLogin();
        Long operator = StpUtil.getLoginIdAsLong();
        AgentDraftService.validId(investigationId);
        validate(body == null ? null : new Request(body.question()));
        List<PromotionFact> facts = body.promotionFacts() == null ? List.of() : body.promotionFacts();
        if (facts.size() > 10) throw new IllegalArgumentException("invalid_promotion_facts");
        String grant = drafts.issue(investigationId, operator, facts);
        try {
            client.post().uri(agentUrl.replaceAll("/$", "")
                            + "/internal/v1/investigations/" + investigationId + "/proposal")
                    .header("X-PulseFlow-Agent-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("question", body.question(), "draft_grant", grant, "promotion_facts", facts))
                    .retrieve().body(JsonNode.class);
        } catch (Exception ignored) {
            throw new IllegalStateException("agent_proposal_unavailable");
        }
        CampaignDraft draft = drafts.review(grant, investigationId, operator);
        return ApiResponse.success(new Response(investigationId, draft.getId(), "DRAFT",
                draft.getValidationStatus(), JsonUtil.fromJson(draft.getDslJson(), CampaignDsl.class),
                draft.getEstimatedAudienceCount(), draft.getProfileDataVersion(), true));
    }

    private void validate(Request body) {
        if (body == null || body.question() == null || body.question().isBlank()
                || body.question().length() > 4000) throw new IllegalArgumentException("invalid_proposal_request");
        if (agentUrl.isBlank() || internalToken.isBlank()) throw new IllegalStateException("agent_unavailable");
    }
}
