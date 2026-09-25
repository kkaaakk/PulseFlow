package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.dsl.CampaignDsl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public, aggregate-only contract for the internal agent tool API. */
public final class AgentToolDtos {
    private AgentToolDtos() {}

    public enum Metric {
        SENT, DELIVERED, CLICKS, CONVERSIONS, CTR, CONVERSION_RATE, ATTRIBUTED_CONVERSIONS
    }

    public enum Dimension { CAMPAIGN, CHANNEL, DAY }

    public enum AttributionDimension { CAMPAIGN, CHANNEL, DAY, MODEL }

    public enum FilterField { CAMPAIGN_ID, CHANNEL }

    public enum Operator { EQ, IN }

    /** Both endpoints are inclusive/exclusive instants; DB buckets use Asia/Shanghai. */
    public record TimeRange(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {}

    public record Filter(FilterField field, Operator operator, List<String> values) {}

    public record QueryRequest(Metric metric, TimeRange timeRange,
                               List<Filter> filters, List<Dimension> dimensions, Integer rowLimit) {}

    public record CompareRequest(Metric metric, TimeRange currentPeriod, TimeRange baselinePeriod,
                                 List<Filter> filters, List<Dimension> dimensions, Integer rowLimit) {}

    public record BreakdownRequest(Metric metric, TimeRange timeRange,
                                   List<Filter> filters, Dimension dimension, Integer rowLimit) {}

    public record AttributionRequest(TimeRange timeRange, List<Filter> filters,
                                     AttributionDimension dimension, Integer rowLimit) {}

    public record AudienceRequest(CampaignDsl dsl) {}

    public record Metadata(String queryId, Instant generatedAt, String dataVersion,
                           String source, List<String> warnings) {}

    /** Null dimension values represent missing values in the source facts. */
    public record MetricRow(Map<Dimension, String> dimensions, BigDecimal value, long sampleSize) {}

    public record QueryResponse(Metadata metadata, Metric metric, TimeRange timeRange,
                                List<Dimension> dimensions, List<MetricRow> rows, long sampleSize) {}

    /** relativeDelta is null when the baseline is zero; counts and rates use the same scale. */
    public record CompareRow(Map<Dimension, String> dimensions, BigDecimal current,
                             BigDecimal baseline, BigDecimal absoluteDelta,
                             BigDecimal relativeDelta, long currentSampleSize,
                             long baselineSampleSize) {}

    public record CompareResponse(Metadata metadata, Metric metric, TimeRange currentPeriod,
                                  TimeRange baselinePeriod, List<Dimension> dimensions,
                                  List<CompareRow> rows, long sampleSize) {}

    public record PerformanceResponse(Metadata metadata, Long campaignId, boolean available,
                                      Long targetAudienceCount, Long sentCount,
                                      Long deliveredCount, Long clickedCount, Long convertedCount,
                                      BigDecimal deliveryRate, BigDecimal clickRate,
                                      BigDecimal conversionRate, Instant calculatedAt) {}

    public record AttributionRow(Map<AttributionDimension, String> dimensions,
                                 long attributionCount, long uniqueConverters) {}

    public record AttributionResponse(Metadata metadata, TimeRange timeRange,
                                      AttributionDimension dimension,
                                      List<AttributionRow> rows) {}

    /** Validation details are codes, never echoed DSL values or raw exception messages. */
    public record Validation(boolean valid, boolean needsConfirmation, List<String> errors,
                             List<String> missingFields) {}

    public record AudienceResponse(Metadata metadata, Long estimatedCount,
                                   Validation validation) {}
}
