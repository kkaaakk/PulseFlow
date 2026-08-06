package com.pulseflow.ai.api;

import com.pulseflow.ai.application.AudienceInsightService;
import com.pulseflow.ai.application.AudiencePreviewResult;
import com.pulseflow.ai.application.CampaignAiDraftService;
import com.pulseflow.ai.application.CampaignContentService;
import com.pulseflow.ai.application.CampaignIntentService;
import com.pulseflow.ai.api.dto.AiCampaignDtos;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.DslValidationResult;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiDraft;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.common.util.JsonUtil;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * REST endpoints for the AI Campaign Copilot — draft creation, editing, and
 * audience insight.
 *
 * <p>Note: the confirm endpoint lives in pulseflow-campaign
 * ({@code CampaignFromAiDraftController}) per design §11.5, because it is a
 * business entry point that ultimately inserts a real campaign.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/campaigns")
@RequiredArgsConstructor
public class AiCampaignController {

    private final CampaignIntentService intentService;
    private final CampaignAiDraftService draftService;
    private final AudienceInsightService insightService;
    private final CampaignContentService contentService;

    @PostMapping("/parse")
    public ApiResponse<AiCampaignDtos.ParseResponse> parse(@RequestBody AiCampaignDtos.ParseRequest req) {
        if (req.getText() == null || req.getText().isBlank()) {
            return ApiResponse.fail(400, "text is required");
        }
        String timezone = req.getTimezone() == null ? ZoneId.systemDefault().toString() : req.getTimezone();
        Long operatorId = currentOperatorId();

        CampaignIntentService.ParseResult result = intentService.parse(operatorId, req.getText(), timezone);

        AiCampaignDtos.EstimatedAudience est = null;
        if (result.estimatedAudience() != null) {
            AudiencePreviewResult p = result.estimatedAudience();
            est = AiCampaignDtos.EstimatedAudience.builder()
                    .count(p.getEstimatedCount())
                    .dataVersion(p.getDataVersion())
                    .calculationMode(p.getCalculationMode())
                    .warnings(p.getWarnings())
                    .build();
        }

        AiCampaignDtos.ParseResponse resp = AiCampaignDtos.ParseResponse.builder()
                .requestId(result.requestId())
                .draftId(result.draftId())
                .status(result.status())
                .dsl(result.dsl())
                .estimatedAudience(est)
                .missingFields(result.missingFields())
                .warnings(result.warnings())
                .build();
        return ApiResponse.success(resp);
    }

    @PutMapping("/drafts/{draftId}")
    public ApiResponse<AiCampaignDtos.DraftResponse> updateDraft(
            @PathVariable Long draftId,
            @RequestBody AiCampaignDtos.UpdateDraftRequest req) {
        if (req.getDsl() == null) {
            return ApiResponse.fail(400, "dsl is required");
        }
        // Convert loose JSON into typed DSL for validation
        CampaignDsl newDsl = JsonUtil.fromJson(JsonUtil.toJson(req.getDsl()), CampaignDsl.class);

        CampaignAiDraftService.DraftUpdateResult updated = draftService.updateDraft(draftId, currentOperatorId(), newDsl);
        CampaignAiDraft draft = updated.draft();
        DslValidationResult validation = updated.validation();

        return ApiResponse.success(AiCampaignDtos.DraftResponse.builder()
                .draftId(draft.getId())
                .status(draft.getValidationStatus())
                .dsl(newDsl)
                .errors(validation.getErrors())
                .warnings(validation.getWarnings())
                .estimatedAudience(buildEstimate(draft))
                .build());
    }

    @PostMapping("/drafts/{draftId}/refresh-preview")
    public ApiResponse<AiCampaignDtos.DraftResponse> refreshPreview(@PathVariable Long draftId) {
        // Re-run audience preview against current DSL (without changing DSL).
        CampaignAiDraft draft = draftService.loadDraft(draftId);
        CampaignDsl dsl = JsonUtil.fromJson(draft.getDslJson(), CampaignDsl.class);
        // We delegate to the intent pipeline's preview service via the draft service.
        // For v1: simply return the stored draft; a re-preview hook can be added later.
        List<String> warnings = draft.getWarningsJson() == null
                ? new ArrayList<>() : JsonUtil.fromJson(draft.getWarningsJson(), List.class);
        return ApiResponse.success(AiCampaignDtos.DraftResponse.builder()
                .draftId(draft.getId())
                .status(draft.getValidationStatus())
                .dsl(dsl)
                .warnings(warnings)
                .estimatedAudience(buildEstimate(draft))
                .build());
    }

    /**
     * Generate AI audience insight for a draft (design §11.3).
     *
     * <p>Input to the LLM is strictly aggregated metrics computed by the Java
     * backend — no individual user rows. The model's output is validated
     * against the input evidence keys; fabricated findings are dropped,
     * fabricated summary numbers cause a 422.</p>
     */
    @PostMapping("/drafts/{draftId}/insight")
    public ApiResponse<AiCampaignDtos.InsightResponse> insight(
            @PathVariable Long draftId,
            @RequestBody(required = false) AiCampaignDtos.InsightRequest req) {
        Long operatorId = currentOperatorId();
        AudienceInsightService.InsightOutput out = insightService.generate(draftId, operatorId);
        AudienceInsightService.DataQuality dq = out.dataQuality();
        return ApiResponse.success(AiCampaignDtos.InsightResponse.builder()
                .requestId(out.requestId())
                .draftId(draftId)
                .metrics(out.metrics())
                .insight(out.insight())
                .dataQuality(AiCampaignDtos.DataQuality.builder()
                        .baselineType(dq.baselineType())
                        .proxyMetrics(dq.proxyMetrics())
                        .unavailableMetrics(dq.unavailableMetrics())
                        .build())
                .build());
    }

    /**
     * Generate three differentiated content variants for a draft (design §11.4).
     *
     * <p>Promotion facts come exclusively from the draft (server-authoritative);
     * the request body may only provide tone, length limits, forbidden words,
     * and an optional audience summary. Invalid variants are dropped by
     * {@link com.pulseflow.ai.guardrail.ContentFactValidator}.</p>
     */
    @PostMapping("/drafts/{draftId}/contents")
    public ApiResponse<AiCampaignDtos.ContentResponse> contents(
            @PathVariable Long draftId,
            @RequestBody(required = false) AiCampaignDtos.ContentRequest req) {
        req = req == null ? AiCampaignDtos.ContentRequest.builder().build() : req;
        CampaignContentService.ContentOutput out = contentService.generate(
                draftId, currentOperatorId(), req.getTone(),
                req.getTitleMaxLength(), req.getBodyMaxLength(),
                req.getVariantCount(), req.getForbiddenWords(),
                req.getAudienceSummary());
        return ApiResponse.success(AiCampaignDtos.ContentResponse.builder()
                .requestId(out.requestId())
                .draftId(draftId)
                .content(out.content())
                .build());
    }

    private AiCampaignDtos.EstimatedAudience buildEstimate(CampaignAiDraft draft) {
        if (draft.getEstimatedAudienceCount() == null) return null;
        return AiCampaignDtos.EstimatedAudience.builder()
                .count(draft.getEstimatedAudienceCount())
                .dataVersion(draft.getProfileDataVersion())
                .calculationMode("SNAPSHOT")
                .build();
    }

    /**
     * Resolve the operator id from the Sa-Token login context (server
     * authoritative). The global SaTokenConfig interceptor already enforces
     * login on {@code /api/**}, so a logged-in session is guaranteed when this
     * is reached in production. The request body's {@code operatorId} field
     * is intentionally ignored to prevent front-end forgery — every AI
     * operation must be traceable to the authenticated operator.
     *
     * <p>Returns {@code null} if no login session is available (e.g. when the
     * controller is invoked outside an HTTP request in tests), so AI audit
     * records an anonymous operator rather than failing.</p>
     */
    private Long currentOperatorId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? null : Long.parseLong(loginId.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
