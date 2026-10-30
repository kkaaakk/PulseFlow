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

    /**
     * 从当前登录会话取得所有者，校验并保存请求中的 Campaign DSL，返回草稿响应。
     */
    @PostMapping
    public ApiResponse<DraftResponse> create(@RequestBody CreateRequest request) {
        return ApiResponse.success(toResponse(drafts.createDraft(request.requestId(), operatorId(), request.dsl())));
    }

    /**
     * 按草稿 ID 和当前操作员加载草稿，所有权校验由服务层执行。
     */
    @GetMapping("/{id}")
    public ApiResponse<DraftResponse> get(@PathVariable Long id) {
        return ApiResponse.success(toResponse(drafts.loadDraft(id, operatorId())));
    }

    /**
     * 用当前操作员提交的新 DSL 更新草稿并重新校验，返回最新状态与校验信息。
     */
    @PutMapping("/{id}")
    public ApiResponse<DraftResponse> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        return ApiResponse.success(toResponse(drafts.updateDraft(id, operatorId(), request.dsl()).draft()));
    }

    /**
     * 要求当前操作员拥有草稿，重新计算并持久化人群预估快照。
     */
    @PostMapping("/{id}/refresh-preview")
    public ApiResponse<DraftResponse> refreshPreview(@PathVariable Long id) {
        return ApiResponse.success(toResponse(drafts.refreshPreview(id, operatorId()).draft()));
    }

    /**
     * 由当前登录操作员确认草稿，事务性创建 DRAFT Campaign 与规则；重复确认复用既有 Campaign。
     */
    @PostMapping("/{id}/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable Long id) {
        CampaignDraftService.ConfirmResult result = drafts.confirmAndCreate(id, operatorId());
        return ApiResponse.success(new ConfirmResponse(result.campaignId(), result.draftId(), result.idempotent()));
    }

    /**
     * 校验当前 Sa-Token 会话并返回操作员数值 ID，用作草稿所有权校验依据。
     */
    private Long operatorId() {
        try {
            return Long.valueOf(StpUtil.getLoginId().toString());
        } catch (Exception e) {
            throw new CampaignForbiddenException("Authentication required");
        }
    }

    /**
     * 将草稿实体及其 JSON 校验信息转换为前端草稿响应。
     */
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
