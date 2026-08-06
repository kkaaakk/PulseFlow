package com.pulseflow.ai.api;

import com.pulseflow.ai.application.CampaignAiDraftService;
import com.pulseflow.ai.api.dto.AiCampaignDtos;
import com.pulseflow.common.model.ApiResponse;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirms an AI Campaign draft into a real campaign.
 *
 * <p>URL prefix is {@code /api/campaigns} because per design §11.5 this is a
 * business entry point — but the controller lives in pulseflow-ai to keep the
 * dependency direction AI→Campaign one-way. The actual campaign/rule insert
 * is performed by {@link CampaignAiDraftService#confirmAndCreate} via the
 * shared Mappers in pulseflow-common; afterwards the existing DecisionEngine
 * / CampaignSelectionJob pipeline owns execution. AI never bypasses this.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class AiCampaignConfirmController {

    private final CampaignAiDraftService draftService;

    @PostMapping("/from-ai-draft/{draftId}")
    public ApiResponse<AiCampaignDtos.ConfirmResponse> confirmFromAiDraft(
            @PathVariable Long draftId,
            @RequestBody(required = false) AiCampaignDtos.ConfirmRequest req) {
        Long operatorId = currentOperatorId();
        CampaignAiDraftService.ConfirmResult result = draftService.confirmAndCreate(draftId, operatorId);
        return ApiResponse.success(AiCampaignDtos.ConfirmResponse.builder()
                .campaignId(result.campaignId())
                .draftId(result.draftId())
                .idempotent(result.idempotent())
                .build());
    }

    /**
     * Resolve the operator id from the Sa-Token login context (server
     * authoritative). The request body's {@code operatorId} is ignored so the
     * front-end cannot forge who confirmed a draft — the resulting campaign is
     * always attributed to the authenticated operator.
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
