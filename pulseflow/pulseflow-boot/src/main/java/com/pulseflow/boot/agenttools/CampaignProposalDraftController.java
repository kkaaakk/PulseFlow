package com.pulseflow.boot.agenttools;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.campaign.draft.CampaignDraft;
import com.pulseflow.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工请求将持久化方案转换为 Java 草稿的入口。
 * User action after the Agent has stopped at its persisted Proposal. */
@RestController
@RequestMapping("/api/campaign-proposals")
@RequiredArgsConstructor
public class CampaignProposalDraftController {
    private final CampaignProposalDraftService drafts;
    private final AgentProposalClient proposals;

    /** 方案生成草稿操作的返回结果。 */
    public record Response(Long draftId, String status, Long estimatedAudienceCount, String dataVersion) {}

    /** 用当前登录操作员创建方案对应的 Java 草稿，返回草稿标识和状态。 */
    @PostMapping("/{proposalId}/draft")
    public ApiResponse<Response> create(@PathVariable String proposalId) {
        StpUtil.checkLogin();
        CampaignDraft draft = drafts.create(proposalId, StpUtil.getLoginIdAsLong());
        // 此时 Java 事务已提交，Agent 关联回写失败可留待重试修复，不撤销已创建草稿。
        try { proposals.markDraftCreated(proposalId, draft.getId()); }
        catch (AgentGatewayException ignored) { /* retry repairs status; committed Java draft remains valid */ }
        return ApiResponse.success(new Response(draft.getId(), draft.getValidationStatus(),
                draft.getEstimatedAudienceCount(), draft.getProfileDataVersion()));
    }
}
