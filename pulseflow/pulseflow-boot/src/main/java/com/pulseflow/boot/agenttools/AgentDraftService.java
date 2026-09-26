package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.campaign.draft.CampaignDraftService;
import com.pulseflow.campaign.dsl.*;
import com.pulseflow.campaign.exception.CampaignConflictException;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.campaign.preview.AudiencePreviewResult;
import com.pulseflow.campaign.preview.AudiencePreviewService;
import com.pulseflow.campaign.validation.CampaignDslValidator;
import com.pulseflow.common.util.JsonUtil;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** OBSERVE credentials alone cannot create drafts; only Java session-issued PROPOSE grants can. */
@Service
@RequiredArgsConstructor
public class AgentDraftService {
    private final JdbcTemplate jdbc;
    private final CampaignDraftService drafts;
    private final CampaignDslValidator validator;
    private final AudiencePreviewService preview;

    public record Proposal(String campaignName, String objective, String rationale,
                           AudienceGroup targetAudience, String channel, CampaignSchedule schedule,
                           FrequencyCap frequencyCap, List<PromotionFact> promotionFacts,
                           List<String> supportingEvidenceIds) {}
    public record Request(String investigationId, Proposal proposal) {}
    public record Response(AgentToolDtos.Metadata metadata, Long draftId, String state,
                           String validationStatus, Long estimatedCount, String dataVersion,
                           boolean requiresHumanConfirmation, String approvalLevel) {}

    public void registerOwner(String investigationId, Long sessionOperatorId) {
        validId(investigationId);
        if (sessionOperatorId == null || sessionOperatorId <= 0) throw forbidden();
        jdbc.update("INSERT INTO agent_investigation_owner (investigation_id,operator_id,created_at) VALUES (?,?,?)",
                investigationId, sessionOperatorId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** Called by the Java authenticated gateway only. Never expose the grant to browsers. */
    public String issue(String investigationId, Long sessionOperatorId) {
        return issue(investigationId, sessionOperatorId, List.of());
    }

    public String issue(String investigationId, Long sessionOperatorId, List<PromotionFact> authorizedFacts) {
        validId(investigationId);
        if (sessionOperatorId == null || sessionOperatorId <= 0) throw forbidden();
        List<Long> owners = jdbc.queryForList(
                "SELECT operator_id FROM agent_investigation_owner WHERE investigation_id=?",
                Long.class, investigationId);
        if (owners.size() != 1 || !sessionOperatorId.equals(owners.get(0))) throw forbidden();
        String id = UUID.randomUUID().toString();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<PromotionFact> facts = authorizedFacts == null ? List.of() : authorizedFacts;
        if (facts.size() > 10) throw invalid();
        for (PromotionFact fact : facts) {
            if (fact == null || fact.getType() == null || fact.getType().isBlank() || fact.getType().length() > 32
                    || (fact.getDescription() != null && fact.getDescription().length() > 1000)
                    || (fact.getValidUntil() != null && fact.getValidUntil().length() > 64)
                    || (fact.getThreshold() != null && fact.getThreshold().signum() < 0)
                    || (fact.getDiscount() != null && fact.getDiscount().signum() < 0)
                    || (fact.getRate() != null && (fact.getRate().signum() <= 0
                        || fact.getRate().compareTo(java.math.BigDecimal.ONE) > 0))) throw invalid();
        }
        jdbc.update("INSERT INTO agent_campaign_draft_grant "
                        + "(id,token_hash,investigation_id,operator_id,expires_at,created_at,authorized_facts_json) VALUES (?,?,?,?,?,?,?)",
                id, hash(token), investigationId, sessionOperatorId, now.plusMinutes(10), now, JsonUtil.toJson(facts));
        return token;
    }

    @Transactional
    @WithSpan("agent-tools.create-draft")
    public Response create(String grantToken, Request request) {
        if (request == null || request.proposal() == null) throw invalid();
        validId(request.investigationId());
        Map<String, Object> grant = requireGrant(grantToken, request.investigationId(), true);
        Long operatorId = ((Number) grant.get("operator_id")).longValue();
        Proposal proposal = request.proposal();
        if (proposal.rationale() == null || proposal.rationale().isBlank()
                || proposal.rationale().length() > 2000
                || proposal.supportingEvidenceIds() == null || proposal.supportingEvidenceIds().isEmpty()
                || proposal.supportingEvidenceIds().size() > 20) throw invalid();
        proposal.supportingEvidenceIds().forEach(AgentDraftService::validId);
        Object authorizedFacts = JsonUtil.fromJson(grant.get("authorized_facts_json").toString(), Object.class);
        Object proposedFacts = JsonUtil.fromJson(JsonUtil.toJson(
                proposal.promotionFacts() == null ? List.of() : proposal.promotionFacts()), Object.class);
        if (!authorizedFacts.equals(proposedFacts)) throw new IllegalArgumentException("unauthorized_promotion_facts");
        String proposalHash = hash(JsonUtil.toJson(proposal));
        Number existing = (Number) grant.get("draft_id");
        if (existing != null) {
            if (!proposalHash.equals(grant.get("proposal_hash"))) {
                throw new CampaignConflictException("draft_grant_already_used");
            }
            CampaignDraft previous = drafts.loadDraft(existing.longValue(), operatorId);
            if ("CONFIRMED".equals(previous.getValidationStatus())) {
                throw new CampaignConflictException("draft_grant_already_used");
            }
            return response(previous);
        }
        CampaignDsl dsl = CampaignDsl.builder().schemaVersion(1).campaignName(proposal.campaignName())
                .objective(proposal.objective()).audience(proposal.targetAudience()).channel(proposal.channel())
                .schedule(proposal.schedule()).frequencyCap(proposal.frequencyCap())
                .promotionFacts(proposal.promotionFacts()).build();
        DslValidationResult checked = validator.validate(dsl);
        if (!checked.getErrors().isEmpty()) throw invalid();
        AudiencePreviewResult audience = preview.preview(dsl);
        if (audience == null || (audience.getWarnings() != null && audience.getWarnings().stream()
                .anyMatch(warning -> warning != null && warning.startsWith("preview failed:")))) {
            throw new IllegalStateException("audience_preview_unavailable");
        }
        CampaignDraft draft = drafts.createDraft(grant.get("id").toString(), operatorId,
                null, dsl, checked, audience);
        jdbc.update("UPDATE agent_campaign_draft_grant SET draft_id=?,proposal_hash=? WHERE id=?",
                draft.getId(), proposalHash, grant.get("id"));
        return response(draft);
    }

    public CampaignDraft review(String token, String investigationId, Long sessionOperatorId) {
        Map<String, Object> grant = requireGrant(token, investigationId, false);
        if (sessionOperatorId == null || !sessionOperatorId.equals(
                ((Number) grant.get("operator_id")).longValue())) throw forbidden();
        Number id = (Number) grant.get("draft_id");
        if (id == null) throw new CampaignConflictException("proposal_did_not_create_draft");
        return drafts.loadDraft(id.longValue(), sessionOperatorId);
    }

    private Map<String, Object> requireGrant(String token, String investigationId, boolean lock) {
        if (token == null || token.length() > 512 || !token.contains(".")) throw forbidden();
        String id = token.substring(0, token.indexOf('.'));
        try { validId(id); } catch (IllegalArgumentException e) { throw forbidden(); }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM agent_campaign_draft_grant WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.size() != 1) throw forbidden();
        Map<String, Object> row = rows.get(0);
        String expected = row.get("token_hash").toString();
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                hash(token).getBytes(StandardCharsets.US_ASCII))
                || !investigationId.equals(row.get("investigation_id"))
                || !((Timestamp) row.get("expires_at")).toLocalDateTime()
                    .isAfter(LocalDateTime.now(ZoneOffset.UTC))) throw forbidden();
        return row;
    }

    private Response response(CampaignDraft draft) {
        AgentToolDtos.Metadata metadata = new AgentToolDtos.Metadata(UUID.randomUUID().toString(),
                Instant.now(), draft.getProfileDataVersion(), "campaign-draft", List.of());
        return new Response(metadata, draft.getId(), "DRAFT", draft.getValidationStatus(),
                draft.getEstimatedAudienceCount(), draft.getProfileDataVersion(), true, "PROPOSE");
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static void validId(String id) {
        try { UUID.fromString(id); } catch (RuntimeException e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_proposal"); }
    private static CampaignForbiddenException forbidden() { return new CampaignForbiddenException("draft_grant_denied"); }
}
