package com.pulseflow.campaign.preview;

import com.pulseflow.campaign.dsl.CampaignDsl;

/**
 * Audience preview / estimation. Implementations translate the DSL into a
 * SQL count over the existing profile/tag/summary tables — never into a new
 * query language.
 */
public interface AudiencePreviewService {

    /**
     * 根据已解析的 Campaign DSL 预估聚合人群规模，并返回计算时间、数据版本及警告；不执行触达。
     */
    AudiencePreviewResult preview(CampaignDsl dsl);

    /**
     * Optional: return the matching userIds (capped) for downstream aggregate
     * computation. v1 returns null/empty when limit exceeded; callers fall
     * back to a SQL aggregate.
     */
    default java.util.List<Long> previewUserIds(CampaignDsl dsl, int limit) {
        return java.util.Collections.emptyList();
    }
}
