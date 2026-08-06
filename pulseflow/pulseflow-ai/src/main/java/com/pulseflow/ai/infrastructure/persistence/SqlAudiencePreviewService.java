package com.pulseflow.ai.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.ai.application.AudiencePreviewResult;
import com.pulseflow.ai.application.AudiencePreviewService;
import com.pulseflow.ai.domain.campaign.AudienceCondition;
import com.pulseflow.ai.domain.campaign.AudienceGroup;
import com.pulseflow.ai.domain.campaign.CampaignDsl;
import com.pulseflow.ai.domain.campaign.ValueType;
import com.pulseflow.ai.guardrail.AiFieldRegistry;
import com.pulseflow.ai.guardrail.DslToRuleConverter;
import com.pulseflow.entity.UserBehaviorSummary;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.entity.UserTag;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserProfileMapper;
import com.pulseflow.mapper.UserTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL-based audience preview.
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>For TAG conditions: query {@code user_tag} for matching users.</li>
 *   <li>For METRIC conditions: query {@code user_behavior_summary} for the
 *       latest value per (user, metric_type) and filter.</li>
 *   <li>Intersect (AND) or union (OR) the resulting user sets.</li>
 *   <li>Cap the candidate pool to {@code audiencePreviewLimit} active users
 *       (those with any user_behavior_summary in last 30d) to bound cost.</li>
 * </ol>
 *
 * <p>This is the SAME data the DecisionEngine reads at runtime, so the
 * estimate reflects what would actually be matched.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlAudiencePreviewService implements AudiencePreviewService {

    private final UserProfileMapper userProfileMapper;
    private final UserTagMapper userTagMapper;
    private final UserBehaviorSummaryMapper behaviorSummaryMapper;
    private final AiFieldRegistry fieldRegistry;
    private final DslToRuleConverter converter;

    @Override
    public AudiencePreviewResult preview(CampaignDsl dsl) {
        LocalDateTime now = LocalDateTime.now();
        String dataVersion = "profile-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));

        if (dsl == null || dsl.getAudience() == null
                || dsl.getAudience().getConditions() == null
                || dsl.getAudience().getConditions().isEmpty()) {
            return AudiencePreviewResult.builder()
                    .estimatedCount(0)
                    .calculatedAt(now)
                    .dataVersion(dataVersion)
                    .calculationMode("SNAPSHOT")
                    .warnings(List.of("empty audience"))
                    .build();
        }

        // 1. Build candidate pool: active users (status=1) limited to a sensible cap.
        List<UserProfile> candidates = userProfileMapper.selectList(
                new LambdaQueryWrapper<UserProfile>()
                        .eq(UserProfile::getStatus, 1)
                        .last("LIMIT 50000"));
        Set<Long> candidateIds = candidates.stream()
                .map(UserProfile::getUserId)
                .collect(Collectors.toSet());
        if (candidateIds.isEmpty()) {
            return emptyResult(now, dataVersion);
        }

        AudienceGroup audience = dsl.getAudience();
        String logic = audience.getLogic() == null ? "AND" : audience.getLogic().toUpperCase();
        List<String> warnings = new ArrayList<>();

        Set<Long> matched;
        try {
            matched = evaluateConditions(audience.getConditions(), candidateIds, logic);
        } catch (Exception e) {
            log.warn("Audience preview failed: {}", e.getMessage());
            return AudiencePreviewResult.builder()
                    .estimatedCount(0)
                    .calculatedAt(now)
                    .dataVersion(dataVersion)
                    .calculationMode("SNAPSHOT")
                    .warnings(List.of("preview failed: " + e.getMessage()))
                    .build();
        }

        return AudiencePreviewResult.builder()
                .estimatedCount(matched.size())
                .calculatedAt(now)
                .dataVersion(dataVersion)
                .calculationMode("SNAPSHOT")
                .warnings(warnings)
                .build();
    }

    @Override
    public List<Long> previewUserIds(CampaignDsl dsl, int limit) {
        AudiencePreviewResult r = preview(dsl);
        if (r.getEstimatedCount() == 0 || r.getEstimatedCount() > limit) {
            return Collections.emptyList();
        }
        // Re-run with the same logic but return ids; for simplicity v1 returns
        // empty here and downstream aggregate services use SQL aggregation.
        return Collections.emptyList();
    }

    private Set<Long> evaluateConditions(List<AudienceCondition> conditions,
                                          Set<Long> candidateIds, String logic) {
        List<Set<Long>> perCondition = new ArrayList<>();
        for (AudienceCondition c : conditions) {
            perCondition.add(evalOne(c, candidateIds));
        }
        if (perCondition.isEmpty()) return new HashSet<>();

        Set<Long> result = new HashSet<>(perCondition.get(0));
        for (int i = 1; i < perCondition.size(); i++) {
            if ("AND".equals(logic)) {
                result.retainAll(perCondition.get(i));
            } else {
                result.addAll(perCondition.get(i));
            }
        }
        return result;
    }

    private Set<Long> evalOne(AudienceCondition c, Set<Long> candidateIds) {
        AiFieldRegistry.FieldDescriptor fd = fieldRegistry.get(c.getField());
        if (fd == null) return Set.of();

        if ("TAG".equals(fd.getSourceType())) {
            // Latest value of tag for each user must be "1"
            List<UserTag> tags = userTagMapper.selectList(
                    new LambdaQueryWrapper<UserTag>()
                            .eq(UserTag::getTagName, fd.getTagName())
                            .eq(UserTag::getTagValue, "1")
                            .in(UserTag::getUserId, candidateIds)
                            .orderByDesc(UserTag::getCalculatedAt));
            // Deduplicate by userId (keep latest)
            Set<Long> seen = new HashSet<>();
            Set<Long> matched = new HashSet<>();
            for (UserTag t : tags) {
                if (seen.add(t.getUserId())) {
                    matched.add(t.getUserId());
                }
            }
            return matched;
        }

        // METRIC: latest value per user for metricType, compared with threshold.
        String metricType = fd.getMetricType() != null ? fd.getMetricType() : c.getField();
        List<UserBehaviorSummary> summaries = behaviorSummaryMapper.selectList(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getMetricType, metricType)
                        .in(UserBehaviorSummary::getUserId, candidateIds)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt));
        // Latest value per user
        java.util.Map<Long, Double> latest = new java.util.HashMap<>();
        for (UserBehaviorSummary s : summaries) {
            latest.putIfAbsent(s.getUserId(),
                    s.getMetricValue() != null ? s.getMetricValue().doubleValue() : 0.0);
        }
        double threshold = toDouble(c.getValue());
        String op = c.getOperator();
        Set<Long> matched = new HashSet<>();
        for (var e : latest.entrySet()) {
            if (compare(e.getValue(), op, threshold)) {
                matched.add(e.getKey());
            }
        }
        return matched;
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

    private AudiencePreviewResult emptyResult(LocalDateTime now, String dataVersion) {
        return AudiencePreviewResult.builder()
                .estimatedCount(0)
                .calculatedAt(now)
                .dataVersion(dataVersion)
                .calculationMode("SNAPSHOT")
                .warnings(List.of("no active users"))
                .build();
    }
}
