package com.pulseflow.ai.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.ai.domain.campaign.AudienceCondition;
import com.pulseflow.ai.domain.campaign.AudienceGroup;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.insight.AudienceMetrics;
import com.pulseflow.ai.guardrail.AiFieldRegistry;
import com.pulseflow.entity.UserBehaviorSummary;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.entity.UserTag;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserProfileMapper;
import com.pulseflow.mapper.UserTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes aggregated {@link AudienceMetrics} for the audience matched by a
 * Campaign DSL. Never returns individual user rows — only counts, sums,
 * ratios, and distributions.
 *
 * <p>Strategy mirrors {@link SqlAudiencePreviewService}: resolve the matched
 * user set via tag/metric conditions, then aggregate over the same set.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudienceMetricsAggregator {

    private final UserProfileMapper userProfileMapper;
    private final UserTagMapper userTagMapper;
    private final UserBehaviorSummaryMapper behaviorSummaryMapper;
    private final AiFieldRegistry fieldRegistry;

    public AudienceMetrics aggregate(CampaignDsl dsl) {
        // 1. Candidate pool
        List<UserProfile> candidates = userProfileMapper.selectList(
                new LambdaQueryWrapper<UserProfile>()
                        .eq(UserProfile::getStatus, 1)
                        .last("LIMIT 50000"));
        Set<Long> candidateIds = candidates.stream()
                .map(UserProfile::getUserId).collect(Collectors.toSet());
        if (candidateIds.isEmpty() || dsl.getAudience() == null) {
            return emptyMetrics();
        }

        // 2. Resolve matched user set (AND of all conditions, OR not fully
        //    supported in v1 aggregator — falls back to AND)
        Set<Long> matched = resolveMatched(dsl.getAudience(), candidateIds);

        if (matched.isEmpty()) {
            return AudienceMetrics.builder()
                    .audienceCount(0)
                    .build();
        }

        // 3. Aggregate metrics
        long audienceCount = matched.size();

        // Tag ratios
        Map<String, BigDecimal> tagRatios = computeTagRatios(matched);

        // active_7d rate: fraction of matched users with active_7d > 0
        BigDecimal activeRate7d = computeMetricPositiveRate(matched, "active_7d");

        // spend_30d average
        BigDecimal avgSpend30d = computeMetricAverage(matched, "spend_30d");

        // order count 30d average (from metric if available, else 0)
        BigDecimal avgOrderCount30d = computeMetricAverage(matched, "order_count_30d");

        // cartWithoutPurchaseRate: derived from cart_count > 0 vs ORDER_PAID count
        // v1 simplification: use ratio of users with active_7d>0 but no spend_30d
        BigDecimal cartWithoutPurchaseRate = computeCartWithoutPurchaseRate(matched);

        // Top categories: not directly available in current schema; leave null
        // memberLevelDistribution: not in user_profile; leave null

        // Baseline (site-wide) — v1 uses the candidate pool as baseline
        Map<String, BigDecimal> baseline = new HashMap<>();
        baseline.put("activeRate7d", computeMetricPositiveRate(candidateIds, "active_7d"));
        baseline.put("averageSpend30d", computeMetricAverage(candidateIds, "spend_30d"));
        baseline.put("cartWithoutPurchaseRate", computeCartWithoutPurchaseRate(candidateIds));

        return AudienceMetrics.builder()
                .audienceCount(audienceCount)
                .activeRate7d(activeRate7d)
                .averageSpend30d(avgSpend30d)
                .averageOrderCount30d(avgOrderCount30d)
                .cartWithoutPurchaseRate(cartWithoutPurchaseRate)
                .highValueRate(tagRatios.get("HIGH_VALUE"))
                .priceSensitiveRate(tagRatios.get("PRICE_SENSITIVE"))
                .churnRiskRate(tagRatios.get("CHURN_RISK"))
                .baseline(baseline)
                .build();
    }

    private Set<Long> resolveMatched(AudienceGroup audience, Set<Long> candidateIds) {
        if (audience.getConditions() == null || audience.getConditions().isEmpty()) {
            return new HashSet<>(candidateIds);
        }
        Set<Long> result = null;
        String logic = audience.getLogic() == null ? "AND" : audience.getLogic().toUpperCase();
        for (AudienceCondition c : audience.getConditions()) {
            Set<Long> one = evalOne(c, candidateIds);
            if (result == null) {
                result = new HashSet<>(one);
            } else if ("AND".equals(logic)) {
                result.retainAll(one);
            } else {
                result.addAll(one);
            }
        }
        return result == null ? new HashSet<>() : result;
    }

    private Set<Long> evalOne(AudienceCondition c, Set<Long> candidateIds) {
        AiFieldRegistry.FieldDescriptor fd = fieldRegistry.get(c.getField());
        if (fd == null) return new HashSet<>();

        if ("TAG".equals(fd.getSourceType())) {
            List<UserTag> tags = userTagMapper.selectList(
                    new LambdaQueryWrapper<UserTag>()
                            .eq(UserTag::getTagName, fd.getTagName())
                            .eq(UserTag::getTagValue, "1")
                            .in(UserTag::getUserId, candidateIds)
                            .orderByDesc(UserTag::getCalculatedAt));
            Set<Long> seen = new HashSet<>();
            Set<Long> matched = new HashSet<>();
            for (UserTag t : tags) {
                if (seen.add(t.getUserId())) matched.add(t.getUserId());
            }
            return matched;
        }
        String metricType = fd.getMetricType() != null ? fd.getMetricType() : c.getField();
        List<UserBehaviorSummary> summaries = behaviorSummaryMapper.selectList(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getMetricType, metricType)
                        .in(UserBehaviorSummary::getUserId, candidateIds)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt));
        Map<Long, Double> latest = new HashMap<>();
        for (UserBehaviorSummary s : summaries) {
            latest.putIfAbsent(s.getUserId(),
                    s.getMetricValue() != null ? s.getMetricValue().doubleValue() : 0.0);
        }
        double threshold = toDouble(c.getValue());
        String op = c.getOperator();
        Set<Long> matched = new HashSet<>();
        for (var e : latest.entrySet()) {
            if (compare(e.getValue(), op, threshold)) matched.add(e.getKey());
        }
        return matched;
    }

    private Map<String, BigDecimal> computeTagRatios(Set<Long> userIds) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (String tag : List.of("HIGH_VALUE", "PRICE_SENSITIVE", "CHURN_RISK")) {
            List<UserTag> tags = userTagMapper.selectList(
                    new LambdaQueryWrapper<UserTag>()
                            .eq(UserTag::getTagName, tag)
                            .eq(UserTag::getTagValue, "1")
                            .in(UserTag::getUserId, userIds)
                            .orderByDesc(UserTag::getCalculatedAt));
            Set<Long> unique = new HashSet<>();
            for (UserTag t : tags) unique.add(t.getUserId());
            out.put(tag, ratio(unique.size(), userIds.size()));
        }
        return out;
    }

    private BigDecimal computeMetricPositiveRate(Set<Long> userIds, String metricType) {
        if (userIds.isEmpty()) return BigDecimal.ZERO;
        List<UserBehaviorSummary> summaries = behaviorSummaryMapper.selectList(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getMetricType, metricType)
                        .in(UserBehaviorSummary::getUserId, userIds)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt));
        Set<Long> positive = new HashSet<>();
        Set<Long> seen = new HashSet<>();
        for (UserBehaviorSummary s : summaries) {
            if (seen.add(s.getUserId()) && s.getMetricValue() != null
                    && s.getMetricValue().compareTo(BigDecimal.ZERO) > 0) {
                positive.add(s.getUserId());
            }
        }
        return ratio(positive.size(), userIds.size());
    }

    private BigDecimal computeMetricAverage(Set<Long> userIds, String metricType) {
        if (userIds.isEmpty()) return BigDecimal.ZERO;
        List<UserBehaviorSummary> summaries = behaviorSummaryMapper.selectList(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getMetricType, metricType)
                        .in(UserBehaviorSummary::getUserId, userIds)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt));
        Map<Long, BigDecimal> latest = new HashMap<>();
        for (UserBehaviorSummary s : summaries) {
            latest.putIfAbsent(s.getUserId(),
                    s.getMetricValue() != null ? s.getMetricValue() : BigDecimal.ZERO);
        }
        if (latest.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (var v : latest.values()) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(latest.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeCartWithoutPurchaseRate(Set<Long> userIds) {
        // Simplified: fraction of users with active_7d>0 but spend_30d==0
        if (userIds.isEmpty()) return BigDecimal.ZERO;
        List<UserBehaviorSummary> active7d = behaviorSummaryMapper.selectList(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getMetricType, "active_7d")
                        .in(UserBehaviorSummary::getUserId, userIds)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt));
        List<UserBehaviorSummary> spend30d = behaviorSummaryMapper.selectList(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getMetricType, "spend_30d")
                        .in(UserBehaviorSummary::getUserId, userIds)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt));
        Map<Long, BigDecimal> latestActive = new HashMap<>();
        for (UserBehaviorSummary s : active7d) {
            latestActive.putIfAbsent(s.getUserId(),
                    s.getMetricValue() != null ? s.getMetricValue() : BigDecimal.ZERO);
        }
        Map<Long, BigDecimal> latestSpend = new HashMap<>();
        for (UserBehaviorSummary s : spend30d) {
            latestSpend.putIfAbsent(s.getUserId(),
                    s.getMetricValue() != null ? s.getMetricValue() : BigDecimal.ZERO);
        }
        int activeNoSpend = 0;
        for (Long uid : userIds) {
            BigDecimal a = latestActive.getOrDefault(uid, BigDecimal.ZERO);
            BigDecimal s = latestSpend.getOrDefault(uid, BigDecimal.ZERO);
            if (a.compareTo(BigDecimal.ZERO) > 0 && s.compareTo(BigDecimal.ZERO) == 0) {
                activeNoSpend++;
            }
        }
        return ratio(activeNoSpend, userIds.size());
    }

    private AudienceMetrics emptyMetrics() {
        return AudienceMetrics.builder().audienceCount(0).build();
    }

    private boolean compare(double left, String op, double right) {
        return switch (op) {
            case "EQ"  -> left == right;
            case "NE"  -> left != right;
            case "GT"  -> left > right;
            case "GTE" -> left >= right;
            case "LT"  -> left < right;
            case "LTE" -> left <= right;
            default -> false;
        };
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private BigDecimal ratio(int part, int total) {
        if (total == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}
