package com.pulseflow.ai.api;

import com.pulseflow.ai.application.CampaignReviewService;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiReview;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.common.util.JsonUtil;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Campaign Review endpoints (design §11.6 / §11.7).
 *
 * <ul>
 *   <li>{@code GET  /api/ai/campaigns/{campaignId}/review} — fetch latest review</li>
 *   <li>{@code POST /api/ai/campaigns/{campaignId}/review/regenerate} — re-run</li>
 * </ul>
 *
 * <p>Access control: the global SaTokenConfig interceptor already enforces
 * login on {@code /api/**}. Both endpoints additionally call
 * {@link CampaignReviewService#requireCampaignOwner} so only the campaign's
 * creator may read or regenerate its review (legacy campaigns with
 * {@code created_by=null} are default-denied). The regenerate endpoint also
 * applies a server-side cooldown
 * ({@code pulseflow.ai.review.regenerate-cooldown-seconds}) to prevent
 * repeated calls from burning model cost — a SUCCESS review cannot be
 * regenerated within the cooldown window. The operator id is taken from the
 * Sa-Token session (not the request body) so every regeneration is
 * attributable to an authenticated user.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/campaigns")
@RequiredArgsConstructor
public class AiCampaignReviewController {

    private final CampaignReviewService reviewService;
    private final AiFeatureProperties properties;

    @GetMapping("/{campaignId}/review")
    public ApiResponse<Map<String, Object>> getReview(@PathVariable Long campaignId) {
        // Ownership check: only the campaign's creator may read its review.
        // Legacy campaigns with created_by=null are default-denied.
        reviewService.requireCampaignOwner(campaignId, currentOperatorId());
        CampaignAiReview review = reviewService.findLatest(campaignId);
        if (review == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(toDto(review));
    }

    @PostMapping("/{campaignId}/review/regenerate")
    public ApiResponse<Map<String, Object>> regenerate(
            @PathVariable Long campaignId,
            @RequestBody(required = false) RegenerateRequest req) {
        Long operatorId = currentOperatorId();
        // Ownership check before any model cost is incurred.
        reviewService.requireCampaignOwner(campaignId, operatorId);

        // Cost guard: refuse to regenerate a SUCCESS review that was produced
        // within the cooldown window. The PROCESSING-state 409 (handled inside
        // reviewService.generate) already prevents concurrent regeneration;
        // this guard prevents a single operator from hammering the model by
        // repeatedly re-running a finished review.
        CampaignAiReview latest = reviewService.findLatest(campaignId);
        long cooldownSec = properties.getReview().getRegenerateCooldownSeconds();
        if (latest != null && "SUCCESS".equals(latest.getStatus())
                && latest.getUpdatedAt() != null) {
            long elapsedSec = Duration.between(latest.getUpdatedAt(), LocalDateTime.now()).getSeconds();
            if (elapsedSec < cooldownSec) {
                return ApiResponse.fail(429, "regenerate too frequent, retry in "
                        + (cooldownSec - elapsedSec) + "s");
            }
        }

        CampaignAiReview review = reviewService.generate(campaignId, operatorId);
        return ApiResponse.success(toDto(review));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toDto(CampaignAiReview review) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("campaignId", review.getCampaignId());
        dto.put("status", review.getStatus());
        dto.put("model", review.getModel());
        dto.put("promptVersion", review.getPromptVersion());
        dto.put("updatedAt", review.getUpdatedAt());
        if (review.getErrorMessage() != null) {
            dto.put("errorMessage", review.getErrorMessage());
        }
        if (review.getFailureCode() != null) {
            dto.put("failureCode", review.getFailureCode());
        }
        if (review.getRetryable() != null) {
            dto.put("retryable", review.getRetryable());
        }
        if (review.getRetryCount() != null) {
            dto.put("retryCount", review.getRetryCount());
        }
        if (review.getReviewJson() != null) {
            dto.put("review", JsonUtil.fromJson(review.getReviewJson(), Map.class));
        }
        return dto;
    }

    /**
     * Resolve the operator id from the Sa-Token login context (server
     * authoritative). Returns {@code null} when no session is available.
     */
    private Long currentOperatorId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? null : Long.parseLong(loginId.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static class RegenerateRequest {
        private Long operatorId;

        public Long getOperatorId() { return operatorId; }
        public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    }
}
