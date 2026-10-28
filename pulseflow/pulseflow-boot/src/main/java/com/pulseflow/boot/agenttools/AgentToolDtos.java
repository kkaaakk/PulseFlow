package com.pulseflow.boot.agenttools;

import com.pulseflow.campaign.dsl.CampaignDsl;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 内部工具的类型化聚合请求和响应契约。
 * Public, aggregate-only contract for the internal agent tool API. */
public final class AgentToolDtos {
    /** 隐藏工具类构造入口，避免创建没有状态的工具类实例。 */
    private AgentToolDtos() {}

    /** 允许查询的聚合业务指标。 */
    public enum Metric {
        SENT, DELIVERED, CLICKS, CONVERSIONS, CTR, CONVERSION_RATE, ATTRIBUTED_CONVERSIONS
    }

    /** 指标查询支持的分组维度。 */
    public enum Dimension { CAMPAIGN, CHANNEL, DAY }

    /** 归因拆解支持的分组维度，含归因模型。 */
    public enum AttributionDimension { CAMPAIGN, CHANNEL, DAY, MODEL }

    /** 聚合查询允许使用的筛选字段。 */
    public enum FilterField { CAMPAIGN_ID, CHANNEL }

    /** 允许使用的比较操作符，避免模型或请求传入任意表达式。 */
    public enum Operator { EQ, IN }

    /**
     * 带时区的左闭右开查询区间，数据库桶按业务时区计算。
     * Both endpoints are inclusive/exclusive instants; DB buckets use Asia/Shanghai. */
    public record TimeRange(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {}

    /** 类型化筛选条件，包含字段、操作符和受限值列表。 */
    public record Filter(FilterField field, Operator operator, List<String> values) {}

    /** 聚合指标查询参数，包含时间、筛选、维度和行数上限。 */
    public record QueryRequest(Metric metric, TimeRange timeRange,
                               List<Filter> filters, List<Dimension> dimensions, Integer rowLimit) {}

    /** 指标当前期与基准期对比参数。 */
    public record CompareRequest(Metric metric, TimeRange currentPeriod, TimeRange baselinePeriod,
                                 List<Filter> filters, List<Dimension> dimensions, Integer rowLimit) {}

    /** 按单一维度拆解聚合指标的请求。 */
    public record BreakdownRequest(Metric metric, TimeRange timeRange,
                                   List<Filter> filters, Dimension dimension, Integer rowLimit) {}

    /** 归因聚合请求，指定时间、维度和筛选条件。 */
    public record AttributionRequest(TimeRange timeRange, List<Filter> filters,
                                     AttributionDimension dimension, Integer rowLimit) {}

    /** 人群预估请求，承载待校验活动 DSL。 */
    public record AudienceRequest(CampaignDsl dsl) {}

    /** 查询追溯元数据，包含 ID、时间、来源、版本及警告。 */
    public record Metadata(String queryId, Instant generatedAt, String dataVersion,
                           String source, List<String> warnings) {}

    /**
     * 单个分组的指标值及样本量，维度空值保留源数据缺失语义。
     * Null dimension values represent missing values in the source facts. */
    public record MetricRow(Map<Dimension, String> dimensions, BigDecimal value, long sampleSize) {}

    /** 聚合查询结果，包含指标、区间、分组行及元数据。 */
    public record QueryResponse(Metadata metadata, Metric metric, TimeRange timeRange,
                                List<Dimension> dimensions, List<MetricRow> rows, long sampleSize) {}

    /**
     * 单个分组的两期值和变化量，零基准时相对变化为空。
     * relativeDelta is null when the baseline is zero; counts and rates use the same scale. */
    public record CompareRow(Map<Dimension, String> dimensions, BigDecimal current,
                             BigDecimal baseline, BigDecimal absoluteDelta,
                             BigDecimal relativeDelta, long currentSampleSize,
                             long baselineSampleSize) {}

    /** 周期比较结果，包含对齐分组和口径警告。 */
    public record CompareResponse(Metadata metadata, Metric metric, TimeRange currentPeriod,
                                  TimeRange baselinePeriod, List<Dimension> dimensions,
                                  List<CompareRow> rows, long sampleSize) {}

    /** 内部工具返回的活动权威绩效。 */
    public record PerformanceResponse(Metadata metadata, Long campaignId, boolean available,
                                      Long targetAudienceCount, Long sentCount,
                                      Long deliveredCount, Long clickedCount, Long convertedCount,
                                      BigDecimal deliveryRate, BigDecimal clickRate,
                                      BigDecimal conversionRate, Instant calculatedAt) {}

    /** 单个归因分组的次数与去重用户数。 */
    public record AttributionRow(Map<AttributionDimension, String> dimensions,
                                 long attributionCount, long uniqueConverters) {}

    /** 归因聚合结果，保留维度、区间和查询来源。 */
    public record AttributionResponse(Metadata metadata, TimeRange timeRange,
                                      AttributionDimension dimension,
                                      List<AttributionRow> rows) {}

    /**
     * 可安全返回的 DSL 校验代码，不回显原始输入或异常正文。
     * Validation details are codes, never echoed DSL values or raw exception messages. */
    public record Validation(boolean valid, boolean needsConfirmation, List<String> errors,
                             List<String> missingFields) {}

    /** 人群预估工具响应，包含校验状态、人数和查询元数据。 */
    public record AudienceResponse(Metadata metadata, Long estimatedCount,
                                   Validation validation) {}
}
