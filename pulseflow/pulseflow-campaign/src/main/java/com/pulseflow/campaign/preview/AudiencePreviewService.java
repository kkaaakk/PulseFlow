package com.pulseflow.campaign.preview;

import com.pulseflow.campaign.dsl.CampaignDsl;

/**
 * 人群预估接口，向调用方返回人数、口径和数据版本。
 *
 * Audience preview / estimation. Implementations translate the DSL into a
 * SQL count over the existing profile/tag/summary tables — never into a new
 * query language.
 */
public interface AudiencePreviewService {

    /** 根据 DSL 返回人数、计算时间、数据版本和预估警告。 */
    AudiencePreviewResult preview(CampaignDsl dsl);

    /**
     * 声明可选的限量用户 ID 预览能力，超限或不支持时由下游走聚合路径。
     *
     * Optional: return the matching userIds (capped) for downstream aggregate
     * computation. v1 returns null/empty when limit exceeded; callers fall
     * back to a SQL aggregate.
     */
    default java.util.List<Long> previewUserIds(CampaignDsl dsl, int limit) {
        return java.util.Collections.emptyList();
    }
}
