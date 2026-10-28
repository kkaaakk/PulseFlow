package com.pulseflow.campaign.draft;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.exception.CampaignForbiddenException;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提供操作员所属草稿的创建、编辑、预估和人工确认接口。
 * Authenticated, operator-owned lifecycle for typed Campaign drafts. */
@RestController
@RequestMapping("/api/campaign-drafts")
@RequiredArgsConstructor
public class CampaignDraftController {
    private final CampaignDraftService drafts;

    /** 草稿创建请求，承载类型化 DSL 及请求幂等信息。 */
    public record CreateRequest(String requestId, CampaignDsl dsl) {}
    /** 草稿编辑请求，包含更新后的 DSL。 */
    public record UpdateRequest(CampaignDsl dsl) {}
    /** 草稿详情响应，含状态、DSL、校验错误、预估与警告。 */
    public record DraftResponse(Long draftId, String status, CampaignDsl dsl,
                                List<String> errors, List<String> warnings,
                                Long estimatedAudienceCount, String dataVersion) {}
    /** 人工确认草稿后的活动关联结果。 */
    public record ConfirmResponse(Long campaignId, Long draftId, boolean idempotent) {}

    /** 用当前操作员身份校验并保存 DSL 草稿，返回校验状态和预估信息。 */
    @PostMapping
    public ApiResponse<DraftResponse> create(@RequestBody CreateRequest request) {
        return ApiResponse.success(toResponse(drafts.createDraft(request.requestId(), operatorId(), request.dsl())));
    }

    /** 校验操作员归属后读取草稿，映射为前端响应。 */
    @GetMapping("/{id}")
    public ApiResponse<DraftResponse> get(@PathVariable Long id) {
        return ApiResponse.success(toResponse(drafts.loadDraft(id, operatorId())));
    }

    /** 按当前操作员更新草稿 DSL，返回重新校验的结果。 */
    @PutMapping("/{id}")
    public ApiResponse<DraftResponse> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        return ApiResponse.success(toResponse(drafts.updateDraft(id, operatorId(), request.dsl()).draft()));
    }

    /** 重新计算当前 DSL 的人群快照并保存，返回最新人数和数据版本。 */
    @PostMapping("/{id}/refresh-preview")
    public ApiResponse<DraftResponse> refreshPreview(@PathVariable Long id) {
        return ApiResponse.success(toResponse(drafts.refreshPreview(id, operatorId()).draft()));
    }

    /** 人工确认有效草稿并创建 DRAFT 活动，重复确认返回同一活动 ID。 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable Long id) {
        CampaignDraftService.ConfirmResult result = drafts.confirmAndCreate(id, operatorId());
        return ApiResponse.success(new ConfirmResponse(result.campaignId(), result.draftId(), result.idempotent()));
    }

    /** 从已认证会话取得操作员 ID，作为草稿归属校验依据。 */
    private Long operatorId() {
        try {
            return Long.valueOf(StpUtil.getLoginId().toString());
        } catch (Exception e) {
            throw new CampaignForbiddenException("Authentication required");
        }
    }

    /** 解析草稿中保存的 DSL、错误和警告，组成前端草稿响应。 */
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
