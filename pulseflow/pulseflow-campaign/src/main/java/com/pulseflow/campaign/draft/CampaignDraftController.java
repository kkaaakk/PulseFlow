package com.pulseflow.campaign.draft;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Authenticated, operator-owned lifecycle for typed Campaign drafts. */
@RestController
@RequestMapping("/api/campaign-drafts")
@RequiredArgsConstructor
public class CampaignDraftController {
    private final CampaignDraftService drafts;

    public record CreateRequest(String requestId, CampaignDsl dsl) {}
    public record UpdateRequest(CampaignDsl dsl) {}
    public record DraftResponse(Long draftId, String status, CampaignDsl dsl,
                                List<String> errors, List<String> warnings,
                                Long estimatedAudienceCount, String dataVersion) {}
    public record ConfirmResponse(Long campaignId, Long draftId, boolean idempotent) {}

    @PostMapping
    public ApiResponse<DraftResponse> create(@RequestBody CreateRequest request) {
        return ApiResponse.success(toResponse(drafts.createDraft(request.requestId(), operatorId(), request.dsl())));
    }

    @GetMapping("/{id}")
    public ApiResponse<DraftResponse> get(@PathVariable Long id) {
        return ApiResponse.success(toResponse(drafts.loadDraft(id, operatorId())));
    }

    @PutMapping("/{id}")
    public ApiResponse<DraftResponse> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        return ApiResponse.success(toResponse(drafts.updateDraft(id, operatorId(), request.dsl()).draft()));
    }

    @PostMapping("/{id}/refresh-preview")
    public ApiResponse<DraftResponse> refreshPreview(@PathVariable Long id) {
        return ApiResponse.success(toResponse(drafts.refreshPreview(id, operatorId()).draft()));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable Long id) {
        CampaignDraftService.ConfirmResult result = drafts.confirmAndCreate(id, operatorId());
        return ApiResponse.success(new ConfirmResponse(result.campaignId(), result.draftId(), result.idempotent()));
    }

    private Long operatorId() {
        try {
            return Long.valueOf(StpUtil.getLoginId().toString());
        } catch (Exception e) {
            throw new CampaignForbiddenException("Authentication required");
        }
    }

    @SuppressWarnings("unchecked")
    private DraftResponse toResponse(CampaignDraft draft) {
        return new DraftResponse(draft.getId(), draft.getValidationStatus(),
                JsonUtil.fromJson(draft.getDslJson(), CampaignDsl.class),
                draft.getValidationErrorsJson() == null ? List.of() :
                        JsonUtil.fromJson(draft.getValidationErrorsJson(), List.class),
                draft.getWarningsJson() == null ? List.of() :
                        JsonUtil.fromJson(draft.getWarningsJson(), List.class),
                draft.getEstimatedAudienceCount(), draft.getProfileDataVersion());
    }
}
