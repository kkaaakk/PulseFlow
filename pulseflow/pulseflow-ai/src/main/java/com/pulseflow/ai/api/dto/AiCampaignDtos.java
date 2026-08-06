package com.pulseflow.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTOs for the AI Campaign endpoints. Kept in one file because they are
 * small and only used by the controller layer.
 */
public final class AiCampaignDtos {

    private AiCampaignDtos() {}

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParseRequest {
        private String text;
        private String timezone;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParseResponse {
        private String requestId;
        private Long draftId;
        private String status;
        private Object dsl;
        private EstimatedAudience estimatedAudience;
        private List<String> missingFields;
        private List<String> warnings;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EstimatedAudience {
        private long count;
        private String dataVersion;
        private String calculationMode;
        private List<String> warnings;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpdateDraftRequest {
        /** Full or partial DSL JSON; server merges with stored draft. */
        private Object dsl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DraftResponse {
        private Long draftId;
        private String status;
        private Object dsl;
        private List<String> errors;
        private List<String> warnings;
        private EstimatedAudience estimatedAudience;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfirmRequest {
        private Long operatorId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfirmResponse {
        private Long campaignId;
        private Long draftId;
        private boolean idempotent;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InsightRequest {
        private Long operatorId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InsightResponse {
        private String requestId;
        private Long draftId;
        private Object metrics;
        private Object insight;
        private DataQuality dataQuality;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DataQuality {
        /** Baseline type: "CANDIDATE_POOL" (v1 proxy) or "SITE_WIDE" (future). */
        private String baselineType;
        /** Metrics that are proxies rather than direct measurements. */
        private List<String> proxyMetrics;
        /** Metrics that are unavailable in the current schema (returned as null). */
        private List<String> unavailableMetrics;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentRequest {
        private String tone;
        private Integer titleMaxLength;
        private Integer bodyMaxLength;
        private Integer variantCount;
        private List<String> forbiddenWords;
        private String audienceSummary;
        private Long operatorId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentResponse {
        private String requestId;
        private Long draftId;
        private Object content;
    }
}
