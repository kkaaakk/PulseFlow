package com.pulseflow.ai.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.DraftStatus;
import com.pulseflow.ai.domain.campaign.DslValidationResult;
import com.pulseflow.ai.guardrail.CampaignDslValidator;
import com.pulseflow.ai.guardrail.DslToRuleConverter;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiDraft;
import com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiDraftMapper;
import com.pulseflow.ai.support.AiConflictException;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiForbiddenException;
import com.pulseflow.ai.support.AiOutputInvalidException;
import com.pulseflow.ai.support.AiResourceNotFoundException;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignRule;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.CampaignRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CRUD + lifecycle for AI Campaign drafts.
 *
 * <p>State machine:</p>
 * <pre>
 * GENERATED / NEEDS_CONFIRMATION / VALIDATED  ──operator edit──►  re-validate
 * VALIDATED  ──confirm──►  CONFIRMED (creates real campaign + rules)
 * any  ──expires_at passed──►  EXPIRED
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAiDraftService {

    private final CampaignAiDraftMapper draftMapper;
    private final CampaignMapper campaignMapper;
    private final CampaignRuleMapper campaignRuleMapper;
    private final CampaignDslValidator validator;
    private final DslToRuleConverter converter;
    private final AiFeatureProperties properties;

    /** Injected separately to preserve the small constructor used by existing unit tests. */
    @Autowired
    private AudiencePreviewService audiencePreviewService;

    /**
     * Persist a freshly generated DSL as a draft.
     * The DSL has already been validated; this method just stores it.
     */
    public CampaignAiDraft saveGeneratedDraft(String requestId, Long operatorId,
                                              String sourceText, CampaignDsl dsl,
                                              DslValidationResult validation,
                                              AudiencePreviewResult preview) {
        DraftStatus status = pickStatus(validation);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(properties.getDraftTtlHours());

        CampaignAiDraft draft = CampaignAiDraft.builder()
                .requestId(requestId)
                .operatorId(operatorId)
                .sourceText(sourceText)
                .schemaVersion(dsl.getSchemaVersion() == null ? 1 : dsl.getSchemaVersion())
                .dslJson(JsonUtil.toJson(dsl))
                .validationStatus(status.name())
                .validationErrorsJson(validation.getErrors().isEmpty() ? null : JsonUtil.toJson(validation.getErrors()))
                .warningsJson(JsonUtil.toJson(combinedWarnings(validation, preview)))
                .estimatedAudienceCount(preview == null ? null : preview.getEstimatedCount())
                .profileDataVersion(preview == null ? null : preview.getDataVersion())
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(expiresAt)
                .build();
        draftMapper.insert(draft);
        log.info("AI draft saved: id={}, requestId={}, status={}",
                draft.getId(), requestId, status);
        return draft;
    }

    /**
     * Update an existing draft's DSL (operator edit). Re-validates.
     * Returns the updated draft and validation result.
     *
     * @param operatorId current operator from Sa-Token session; must own the draft
     */
    public DraftUpdateResult updateDraft(Long draftId, Long operatorId, CampaignDsl newDsl) {
        CampaignAiDraft draft = loadDraftOrThrow(draftId);
        requireDraftOwner(draft, operatorId);
        if (draft.getValidationStatus().equals(DraftStatus.CONFIRMED.name())) {
            throw new AiConflictException("Cannot edit a CONFIRMED draft");
        }
        if (isExpired(draft)) {
            draft = markExpired(draft);
            throw new AiConflictException("Draft " + draftId + " is expired");
        }

        DslValidationResult validation = validator.validate(newDsl);
        DraftStatus newStatus = pickStatus(validation);

        draft.setSchemaVersion(newDsl.getSchemaVersion() == null ? 1 : newDsl.getSchemaVersion());
        draft.setDslJson(JsonUtil.toJson(newDsl));
        draft.setValidationStatus(newStatus.name());
        draft.setValidationErrorsJson(validation.getErrors().isEmpty() ? null : JsonUtil.toJson(validation.getErrors()));
        draft.setWarningsJson(JsonUtil.toJson(validation.getWarnings()));
        draft.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(draft);

        return new DraftUpdateResult(draft, validation);
    }

    /**
     * Recalculate the audience snapshot for the current DSL.
     *
     * <p>The old controller implementation returned the values already stored
     * on the draft, which made a button named {@code refresh-preview} a no-op.
     * This method keeps the validator as the source of truth and persists the
     * new count/data version returned by the actual preview service.</p>
     */
    public DraftUpdateResult refreshPreview(Long draftId, Long operatorId) {
        CampaignAiDraft draft = loadDraftOrThrow(draftId);
        requireDraftOwner(draft, operatorId);
        if (DraftStatus.CONFIRMED.name().equals(draft.getValidationStatus())) {
            throw new AiConflictException("Cannot refresh a CONFIRMED draft");
        }
        if (isExpired(draft)) {
            markExpired(draft);
            throw new AiConflictException("Draft " + draftId + " is expired");
        }
        if (audiencePreviewService == null) {
            throw new IllegalStateException("Audience preview service is not available");
        }

        CampaignDsl dsl = JsonUtil.fromJson(draft.getDslJson(), CampaignDsl.class);
        DslValidationResult validation = validator.validate(dsl);
        AudiencePreviewResult preview = null;
        if (validation.isValid()) {
            try {
                preview = audiencePreviewService.preview(dsl);
            } catch (Exception e) {
                log.warn("Audience preview refresh failed for draft {}: {}", draftId, e.getMessage());
                preview = AudiencePreviewResult.builder()
                        .estimatedCount(0)
                        .calculationMode("SNAPSHOT")
                        .warnings(List.of("preview failed: " + e.getMessage()))
                        .build();
            }
        }
        DraftStatus status = pickStatus(validation);

        draft.setSchemaVersion(dsl.getSchemaVersion() == null ? 1 : dsl.getSchemaVersion());
        draft.setValidationStatus(status.name());
        draft.setValidationErrorsJson(validation.getErrors().isEmpty()
                ? null : JsonUtil.toJson(validation.getErrors()));
        draft.setWarningsJson(JsonUtil.toJson(combinedWarnings(validation, preview)));
        draft.setEstimatedAudienceCount(preview == null ? null : preview.getEstimatedCount());
        draft.setProfileDataVersion(preview == null ? null : preview.getDataVersion());
        draft.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(draft);
        return new DraftUpdateResult(draft, validation);
    }

    /**
     * Confirm a VALIDATED draft and create the real Campaign + rules.
     * Re-validates before commit. Idempotent on CONFIRMED state.
     */
    @Transactional
    public ConfirmResult confirmAndCreate(Long draftId, Long operatorId) {
        CampaignAiDraft draft = loadDraftOrThrow(draftId);
        requireDraftOwner(draft, operatorId);

        if (DraftStatus.CONFIRMED.name().equals(draft.getValidationStatus())
                && draft.getConfirmedCampaignId() != null) {
            // Idempotent re-confirm
            return new ConfirmResult(draft.getConfirmedCampaignId(), draft.getId(), true);
        }
        if (DraftStatus.EXPIRED.name().equals(draft.getValidationStatus()) || isExpired(draft)) {
            markExpired(draft);
            throw new AiConflictException("Draft " + draftId + " is expired");
        }
        if (!DraftStatus.VALIDATED.name().equals(draft.getValidationStatus())) {
            throw new AiConflictException("Draft " + draftId + " is not VALIDATED (current="
                    + draft.getValidationStatus() + ")");
        }

        CampaignDsl dsl = JsonUtil.fromJson(draft.getDslJson(), CampaignDsl.class);

        // Re-validate (field registry may have changed since draft creation)
        DslValidationResult reValidation = validator.validate(dsl);
        if (!reValidation.isValid() || reValidation.isNeedsConfirmation()) {
            draft.setValidationStatus(DraftStatus.INVALID.name());
            draft.setValidationErrorsJson(JsonUtil.toJson(reValidation.getErrors()));
            draft.setUpdatedAt(LocalDateTime.now());
            draftMapper.updateById(draft);
            throw new AiOutputInvalidException(AiErrorCode.AI_OUTPUT_SCHEMA_INVALID,
                    "Draft failed re-validation: " + reValidation.getErrors());
        }

        // Build Campaign row
        OffsetDateTime sendAt = OffsetDateTime.parse(dsl.getSchedule().getSendAt());
        LocalDateTime sendAtLocal = sendAt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

        Campaign campaign = Campaign.builder()
                .name(dsl.getCampaignName())
                .description("[OBJ:" + dsl.getObjective() + "] " + buildDescription(dsl))
                .triggerType("SCHEDULED")
                .channel(dsl.getChannel())
                .messageTemplate(buildMessageTemplate(dsl))
                .createdBy(operatorId)
                .userDailyLimit(pickDailyLimit(dsl))
                .campaignWeeklyLimit(pickWeeklyLimit(dsl))
                .status("DRAFT")
                .startTime(sendAtLocal)
                .nextTriggerAt(sendAtLocal)
                .build();
        campaignMapper.insert(campaign);

        // Build Campaign rules
        List<DslToRuleConverter.ConvertedRule> rules = converter.convert(dsl);
        for (DslToRuleConverter.ConvertedRule r : rules) {
            CampaignRule rule = CampaignRule.builder()
                    .campaignId(campaign.getId())
                    .ruleName(r.ruleName())
                    .ruleType(r.ruleType())
                    .ruleConfig(r.ruleConfigJson())
                    .priority(0)
                    .enabled(1)
                    .build();
            campaignRuleMapper.insert(rule);
        }

        // Mark draft confirmed
        draft.setValidationStatus(DraftStatus.CONFIRMED.name());
        draft.setConfirmedCampaignId(campaign.getId());
        draft.setConfirmedAt(LocalDateTime.now());
        draft.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(draft);

        log.info("AI draft {} confirmed → campaign {}", draftId, campaign.getId());
        return new ConfirmResult(campaign.getId(), draft.getId(), false);
    }

    public CampaignAiDraft loadDraft(Long draftId) {
        return loadDraftOrThrow(draftId);
    }

    public CampaignDsl loadDsl(Long draftId) {
        return JsonUtil.fromJson(loadDraftOrThrow(draftId).getDslJson(), CampaignDsl.class);
    }

    // ---------- helpers ----------

    private CampaignAiDraft loadDraftOrThrow(Long draftId) {
        CampaignAiDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new AiResourceNotFoundException("Draft not found: " + draftId);
        }
        return draft;
    }

    /**
     * Verify that the current operator owns the draft. Throws 403 if not.
     * Null operatorId (no session) is allowed only for system-initiated calls
     * (e.g. job) where operatorId is genuinely absent — but for interactive
     * endpoints the controller always passes a non-null operatorId.
     */
    private void requireDraftOwner(CampaignAiDraft draft, Long operatorId) {
        if (operatorId == null) return; // system call (job)
        if (draft.getOperatorId() == null) return; // legacy draft without owner
        if (!operatorId.equals(draft.getOperatorId())) {
            throw new AiForbiddenException(
                    "Operator " + operatorId + " does not own draft " + draft.getId());
        }
    }

    private DraftStatus pickStatus(DslValidationResult v) {
        if (v.isValid()) return DraftStatus.VALIDATED;
        if (v.isNeedsConfirmation()) return DraftStatus.NEEDS_CONFIRMATION;
        return DraftStatus.INVALID;
    }

    private List<String> combinedWarnings(DslValidationResult v, AudiencePreviewResult p) {
        List<String> out = new ArrayList<>(v.getWarnings());
        if (p != null && p.getWarnings() != null) out.addAll(p.getWarnings());
        return out;
    }

    private boolean isExpired(CampaignAiDraft draft) {
        return draft.getExpiresAt() != null && LocalDateTime.now().isAfter(draft.getExpiresAt());
    }

    private CampaignAiDraft markExpired(CampaignAiDraft draft) {
        if (!DraftStatus.EXPIRED.name().equals(draft.getValidationStatus())) {
            draft.setValidationStatus(DraftStatus.EXPIRED.name());
            draft.setUpdatedAt(LocalDateTime.now());
            draftMapper.updateById(draft);
        }
        return draft;
    }

    private int pickDailyLimit(CampaignDsl dsl) {
        if (dsl.getFrequencyCap() == null) return 3;
        Integer window = dsl.getFrequencyCap().getWindowHours();
        Integer max = dsl.getFrequencyCap().getMaxTimes();
        if (window != null && window <= 24) return max == null ? 3 : max;
        return 3; // default user_daily_limit
    }

    private int pickWeeklyLimit(CampaignDsl dsl) {
        if (dsl.getFrequencyCap() == null) return 1;
        Integer window = dsl.getFrequencyCap().getWindowHours();
        Integer max = dsl.getFrequencyCap().getMaxTimes();
        if (window != null && window > 24 && window <= 168) return max == null ? 1 : max;
        return 1;
    }

    private String buildDescription(CampaignDsl dsl) {
        StringBuilder sb = new StringBuilder();
        if (dsl.getAudience() != null && dsl.getAudience().getConditions() != null) {
            sb.append("audience(").append(dsl.getAudience().getLogic()).append(",")
                    .append(dsl.getAudience().getConditions().size()).append(" conditions); ");
        }
        if (dsl.getPromotionFacts() != null && !dsl.getPromotionFacts().isEmpty()) {
            sb.append("promotion=").append(dsl.getPromotionFacts().size()).append(" fact(s)");
        }
        return sb.toString();
    }

    private String buildMessageTemplate(CampaignDsl dsl) {
        // v1: use first promotion fact as the message template; the operator
        // picks a content variant from /contents and overrides this.
        if (dsl.getPromotionFacts() == null || dsl.getPromotionFacts().isEmpty()) {
            return dsl.getCampaignName();
        }
        var first = dsl.getPromotionFacts().get(0);
        if (first.getThreshold() != null && first.getDiscount() != null) {
            return "满" + first.getThreshold().intValue() + "减" + first.getDiscount().intValue();
        }
        return first.getDescription() == null ? dsl.getCampaignName() : first.getDescription();
    }

    /**
     * Returned by {@link #updateDraft}.
     */
    public record DraftUpdateResult(CampaignAiDraft draft, DslValidationResult validation) {}

    /**
     * Returned by {@link #confirmAndCreate}.
     */
    public record ConfirmResult(Long campaignId, Long draftId, boolean idempotent) {}
}
