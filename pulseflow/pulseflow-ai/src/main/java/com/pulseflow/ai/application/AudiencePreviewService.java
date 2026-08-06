package com.pulseflow.ai.application;

import com.pulseflow.ai.domain.campaign.CampaignDsl;

/**
 * Audience preview / estimation. Implementations translate the DSL into a
 * SQL count over the existing profile/tag/summary tables — never into a new
 * query language.
 */
public interface AudiencePreviewService {

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
