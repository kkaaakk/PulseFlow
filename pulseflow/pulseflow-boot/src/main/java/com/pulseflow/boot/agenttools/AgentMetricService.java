package com.pulseflow.boot.agenttools;

import com.pulseflow.boot.agenttools.AgentToolDtos.*;
import com.pulseflow.campaign.analytics.PerformanceSummaryCalculator;
import com.pulseflow.common.enums.ChannelType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Fixed aggregate queries. No SQL fragment or column identifier comes from model text. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentMetricService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Comparator<String> BUCKET_ORDER = Comparator.nullsLast(String::compareTo);
    private static final String NO_DIMENSION = "";
    private final NamedParameterJdbcTemplate jdbc;

    public QueryResponse query(QueryRequest request) {
        if (request == null) throw invalid("invalid_tool_request");
        requireMetric(request.metric());
        Dimension dimension = dimension(request.dimensions());
        int limit = rowLimit(request.rowLimit());
        Map<String, Counts> counts = readCounts(request.timeRange(), parseFilters(request.filters()), dimension);
        List<String> warnings = new ArrayList<>();
        List<String> keys = limitedKeys(counts.keySet(), limit, warnings);
        List<MetricRow> rows = keys.stream().map(key -> {
            Counts c = counts.get(key);
            return new MetricRow(dimensionMap(dimension, key), value(request.metric(), c),
                    sampleSize(request.metric(), c));
        }).toList();
        zeroDenominatorWarning(request.metric(), counts, keys, warnings);
        return new QueryResponse(metadata("campaign-facts", warnings), request.metric(),
                request.timeRange(), dimensions(dimension), rows,
                rows.stream().mapToLong(MetricRow::sampleSize).sum());
    }

    public QueryResponse breakdown(BreakdownRequest request) {
        if (request == null) throw invalid("invalid_tool_request");
        if (request.dimension() == null) throw invalid("invalid_dimension");
        return query(new QueryRequest(request.metric(), request.timeRange(), request.filters(),
                List.of(request.dimension()), request.rowLimit()));
    }

    public CompareResponse compare(CompareRequest request) {
        if (request == null) throw invalid("invalid_tool_request");
        requireMetric(request.metric());
        Dimension dimension = dimension(request.dimensions());
        if (dimension == Dimension.DAY) throw invalid("invalid_dimension");
        int limit = rowLimit(request.rowLimit());
        FilterSpec filters = parseFilters(request.filters());
        Map<String, Counts> current = readCounts(request.currentPeriod(), filters, dimension);
        Map<String, Counts> baseline = readCounts(request.baselinePeriod(), filters, dimension);
        Set<String> allKeys = new TreeSet<>(BUCKET_ORDER);
        allKeys.addAll(current.keySet());
        allKeys.addAll(baseline.keySet());
        List<String> warnings = new ArrayList<>();
        List<String> keys = limitedKeys(allKeys, limit, warnings);
        List<CompareRow> rows = new ArrayList<>();
        for (String key : keys) {
            Counts now = current.getOrDefault(key, Counts.ZERO);
            Counts before = baseline.getOrDefault(key, Counts.ZERO);
            BigDecimal currentValue = value(request.metric(), now);
            BigDecimal baselineValue = value(request.metric(), before);
            BigDecimal delta = currentValue.subtract(baselineValue);
            BigDecimal relative = baselineValue.signum() == 0 ? null
                    : delta.divide(baselineValue, 4, RoundingMode.HALF_UP);
            if (relative == null && !warnings.contains("baseline_zero")) warnings.add("baseline_zero");
            rows.add(new CompareRow(dimensionMap(dimension, key), currentValue, baselineValue,
                    delta, relative, sampleSize(request.metric(), now), sampleSize(request.metric(), before)));
        }
        zeroDenominatorWarning(request.metric(), current, keys, warnings);
        zeroDenominatorWarning(request.metric(), baseline, keys, warnings);
        return new CompareResponse(metadata("campaign-facts", warnings), request.metric(),
                request.currentPeriod(), request.baselinePeriod(), dimensions(dimension), rows,
                rows.stream().mapToLong(CompareRow::currentSampleSize).sum());
    }

    public AttributionResponse attribution(AttributionRequest request) {
        if (request == null) throw invalid("invalid_tool_request");
        if (request.dimension() == null) throw invalid("invalid_dimension");
        int limit = rowLimit(request.rowLimit());
        FilterSpec filters = parseFilters(request.filters());
        MapSqlParameterSource params = parameters(request.timeRange(), filters);
        String bucket = attributionBucket(request.dimension());
        String sql = "SELECT " + bucket + " AS bucket, COUNT(*) AS total, "
                + "COUNT(DISTINCT a.user_id) AS unique_count "
                + "FROM attribution_record a LEFT JOIN delivery_task t ON t.id = a.task_id "
                + "WHERE a.credited_at >= :fromTime AND a.credited_at < :toTime"
                + filterSql("a.campaign_id", "t.channel", filters)
                + " GROUP BY " + bucket + " ORDER BY bucket IS NULL, bucket LIMIT " + (limit + 1);
        List<Map<String, Object>> facts = jdbc.queryForList(sql, params);
        List<String> warnings = facts.size() > limit ? List.of("row_limit_reached") : List.of();
        List<AttributionRow> rows = facts.stream().limit(limit).map(f -> {
            Map<AttributionDimension, String> dims = new LinkedHashMap<>();
            dims.put(request.dimension(), string(f.get("bucket")));
            return new AttributionRow(dims, number(f.get("total")), number(f.get("unique_count")));
        }).toList();
        return new AttributionResponse(metadata("attribution-record", warnings), request.timeRange(),
                request.dimension(), rows);
    }

    private Map<String, Counts> readCounts(TimeRange range, FilterSpec filters, Dimension dimension) {
        MapSqlParameterSource params = parameters(range, filters);
        Map<String, Counts> result = new HashMap<>();
        String deliveryBucket = bucket(dimension, "d.campaign_id", "d.channel", "d.sent_at");
        String deliverySql = "SELECT " + deliveryBucket + " AS bucket, COUNT(*) AS total, "
                + "COALESCE(SUM(CASE WHEN d.status IN ('SENT','DELIVERED') THEN 1 ELSE 0 END),0) "
                + "AS delivered FROM delivery_record d "
                + "WHERE d.sent_at >= :fromTime AND d.sent_at < :toTime"
                + filterSql("d.campaign_id", "d.channel", filters) + grouping(deliveryBucket, dimension);
        for (Map<String, Object> fact : jdbc.queryForList(deliverySql, params)) {
            Counts c = result.computeIfAbsent(key(fact, dimension), ignored -> new Counts());
            c.sent = number(fact.get("total"));
            c.delivered = number(fact.get("delivered"));
        }
        String clickBucket = bucket(dimension, "t.campaign_id", "t.channel", "c.click_time");
        String clickSql = "SELECT " + clickBucket + " AS bucket, COUNT(DISTINCT c.user_id) AS total "
                + "FROM click_event c JOIN delivery_task t ON t.id = c.task_id "
                + "WHERE c.click_time >= :fromTime AND c.click_time < :toTime"
                + filterSql("t.campaign_id", "t.channel", filters) + grouping(clickBucket, dimension);
        for (Map<String, Object> fact : jdbc.queryForList(clickSql, params)) {
            result.computeIfAbsent(key(fact, dimension), ignored -> new Counts()).clicked =
                    number(fact.get("total"));
        }
        String attributionBucket = bucket(dimension, "a.campaign_id", "t.channel", "a.credited_at");
        String attributionSql = "SELECT " + attributionBucket + " AS bucket, "
                + "COUNT(DISTINCT a.user_id) AS converted, COUNT(*) AS attributed "
                + "FROM attribution_record a LEFT JOIN delivery_task t ON t.id = a.task_id "
                + "WHERE a.credited_at >= :fromTime AND a.credited_at < :toTime"
                + filterSql("a.campaign_id", "t.channel", filters) + grouping(attributionBucket, dimension);
        for (Map<String, Object> fact : jdbc.queryForList(attributionSql, params)) {
            Counts c = result.computeIfAbsent(key(fact, dimension), ignored -> new Counts());
            c.converted = number(fact.get("converted"));
            c.attributed = number(fact.get("attributed"));
        }
        if (dimension == null && result.isEmpty()) result.put(NO_DIMENSION, Counts.ZERO);
        return result;
    }

    private String bucket(Dimension dimension, String campaign, String channel, String time) {
        if (dimension == null) return "NULL";
        return switch (dimension) {
            case CAMPAIGN -> campaign;
            case CHANNEL -> channel;
            case DAY -> "DATE(" + time + ")";
        };
    }

    private String attributionBucket(AttributionDimension dimension) {
        return switch (dimension) {
            case CAMPAIGN -> "a.campaign_id";
            case CHANNEL -> "t.channel";
            case DAY -> "DATE(a.credited_at)";
            case MODEL -> "a.attribution_model";
        };
    }

    private String grouping(String bucket, Dimension dimension) {
        return dimension == null ? "" : " GROUP BY " + bucket
                + " ORDER BY bucket IS NULL, bucket LIMIT 101";
    }

    private String filterSql(String campaign, String channel, FilterSpec filters) {
        StringBuilder sql = new StringBuilder();
        if (!filters.campaignIds().isEmpty()) sql.append(" AND ").append(campaign).append(" IN (:campaignIds)");
        if (!filters.channels().isEmpty()) sql.append(" AND ").append(channel).append(" IN (:channels)");
        return sql.toString();
    }

    private MapSqlParameterSource parameters(TimeRange range, FilterSpec filters) {
        validateRange(range);
        return new MapSqlParameterSource()
                .addValue("fromTime", LocalDateTime.ofInstant(range.fromInclusive().toInstant(), BUSINESS_ZONE))
                .addValue("toTime", LocalDateTime.ofInstant(range.toExclusive().toInstant(), BUSINESS_ZONE))
                .addValue("campaignIds", filters.campaignIds())
                .addValue("channels", filters.channels());
    }

    private FilterSpec parseFilters(List<Filter> filters) {
        if (filters == null || filters.isEmpty()) return new FilterSpec(List.of(), List.of());
        if (filters.size() > 2) throw invalid("unsupported_filter");
        Set<FilterField> seen = EnumSet.noneOf(FilterField.class);
        List<Long> campaigns = List.of();
        List<String> channels = List.of();
        for (Filter filter : filters) {
            if (filter == null || filter.field() == null || filter.operator() == null
                    || filter.values() == null || filter.values().isEmpty()
                    || filter.values().stream().anyMatch(value -> value == null || value.isBlank())
                    || filter.values().size() > 10 || !seen.add(filter.field())
                    || (filter.operator() == Operator.EQ && filter.values().size() != 1)) {
                throw invalid("unsupported_filter");
            }
            if (filter.field() == FilterField.CAMPAIGN_ID) {
                try {
                    campaigns = filter.values().stream().map(Long::valueOf).toList();
                    if (campaigns.stream().anyMatch(id -> id <= 0)) throw invalid("unsupported_filter");
                } catch (NumberFormatException e) {
                    throw invalid("unsupported_filter");
                }
            } else if (filter.field() == FilterField.CHANNEL) {
                channels = filter.values().stream().map(value -> {
                    try {
                        return ChannelType.valueOf(value).name();
                    } catch (RuntimeException e) {
                        throw invalid("unsupported_filter");
                    }
                }).toList();
            }
        }
        return new FilterSpec(campaigns, channels);
    }

    private void validateRange(TimeRange range) {
        if (range == null || range.fromInclusive() == null || range.toExclusive() == null) {
            throw invalid("invalid_time_range");
        }
        Instant start = range.fromInclusive().toInstant();
        Instant end = range.toExclusive().toInstant();
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0) {
            throw invalid("invalid_time_range");
        }
    }

    private Dimension dimension(List<Dimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) return null;
        if (dimensions.size() != 1 || dimensions.get(0) == null) throw invalid("invalid_dimension");
        return dimensions.get(0);
    }

    private List<Dimension> dimensions(Dimension dimension) {
        return dimension == null ? List.of() : List.of(dimension);
    }

    private int rowLimit(Integer requested) {
        if (requested == null) return 50;
        if (requested < 1 || requested > 100) throw invalid("invalid_row_limit");
        return requested;
    }

    private List<String> limitedKeys(Set<String> keys, int limit, List<String> warnings) {
        List<String> ordered = keys.stream().sorted(BUCKET_ORDER).toList();
        if (ordered.size() > limit) warnings.add("row_limit_reached");
        return ordered.stream().limit(limit).toList();
    }

    private Map<Dimension, String> dimensionMap(Dimension dimension, String key) {
        if (dimension == null) return Map.of();
        Map<Dimension, String> values = new LinkedHashMap<>();
        values.put(dimension, key);
        return values;
    }

    private String key(Map<String, Object> fact, Dimension dimension) {
        return dimension == null ? NO_DIMENSION : string(fact.get("bucket"));
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private BigDecimal value(Metric metric, Counts counts) {
        return switch (metric) {
            case SENT -> BigDecimal.valueOf(counts.sent);
            case DELIVERED -> BigDecimal.valueOf(counts.delivered);
            case CLICKS -> BigDecimal.valueOf(counts.clicked);
            case CONVERSIONS -> BigDecimal.valueOf(counts.converted);
            case ATTRIBUTED_CONVERSIONS -> BigDecimal.valueOf(counts.attributed);
            case CTR -> PerformanceSummaryCalculator.rate(counts.clicked, counts.delivered);
            case CONVERSION_RATE -> PerformanceSummaryCalculator.rate(counts.converted, counts.clicked);
        };
    }

    private long sampleSize(Metric metric, Counts counts) {
        return switch (metric) {
            case CTR -> counts.delivered;
            case CONVERSION_RATE -> counts.clicked;
            case SENT -> counts.sent;
            case DELIVERED -> counts.delivered;
            case CLICKS -> counts.clicked;
            case CONVERSIONS -> counts.converted;
            case ATTRIBUTED_CONVERSIONS -> counts.attributed;
        };
    }

    private void zeroDenominatorWarning(Metric metric, Map<String, Counts> data,
                                        List<String> keys, List<String> warnings) {
        if (metric != Metric.CTR && metric != Metric.CONVERSION_RATE) return;
        if (keys.stream().map(data::get).filter(c -> c != null).anyMatch(c -> sampleSize(metric, c) == 0)
                && !warnings.contains("zero_denominator_rate_is_zero")) {
            warnings.add("zero_denominator_rate_is_zero");
        }
    }

    private void requireMetric(Metric metric) {
        if (metric == null) throw invalid("invalid_metric");
    }

    private IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    static Metadata metadata(String source, List<String> warnings) {
        return new Metadata(UUID.randomUUID().toString(), Instant.now(), null, source, List.copyOf(warnings));
    }

    private record FilterSpec(List<Long> campaignIds, List<String> channels) {}
    private static final class Counts {
        static final Counts ZERO = new Counts();
        long sent;
        long delivered;
        long clicked;
        long converted;
        long attributed;
    }
}
