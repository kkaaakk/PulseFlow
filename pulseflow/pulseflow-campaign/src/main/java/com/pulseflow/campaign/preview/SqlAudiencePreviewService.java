package com.pulseflow.campaign.preview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.campaign.decision.DecisionEngine;
import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.validation.CampaignFieldRegistry;
import com.pulseflow.campaign.validation.DslToRuleConverter;
import com.pulseflow.entity.CampaignRule;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.mapper.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Estimates the SCHEDULED audience before channel preferences and delivery-time frequency checks. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlAudiencePreviewService implements AudiencePreviewService {

    private static final int CANDIDATE_LIMIT = 50_000;
    private static final int PAGE_SIZE = 500;

    private final UserProfileMapper userProfileMapper;
    private final CampaignFieldRegistry fieldRegistry;
    private final DslToRuleConverter converter;
    private final DecisionEngine decisionEngine;

    @Override
    public AudiencePreviewResult preview(CampaignDsl dsl) {
        LocalDateTime now = LocalDateTime.now();
        AudiencePreviewResult.AudiencePreviewResultBuilder result = AudiencePreviewResult.builder()
                .calculatedAt(now)
                .dataVersion("profile-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")))
                .calculationMode("SNAPSHOT");

        try {
            List<Long> candidates = enabledCandidateIds();
            List<Long> matched = match(dsl, candidates, now);
            List<String> warnings = candidates.size() == CANDIDATE_LIMIT
                    ? List.of("preview limited to 50000 enabled accounts") : List.of();
            return result.estimatedCount(matched.size()).warnings(warnings).build();
        } catch (RuntimeException e) {
            log.warn("Audience preview failed", e);
            return result.estimatedCount(0).warnings(List.of("preview failed: " + e.getMessage())).build();
        }
    }

    @Override
    public List<Long> previewUserIds(CampaignDsl dsl, int limit) {
        List<Long> matched = match(dsl, enabledCandidateIds(), LocalDateTime.now());
        return matched.size() <= limit ? matched : List.of();
    }

    public List<Long> enabledCandidateIds() {
        return userProfileMapper.selectList(new LambdaQueryWrapper<UserProfile>()
                        .select(UserProfile::getUserId)
                        .eq(UserProfile::getStatus, 1)
                        .orderByAsc(UserProfile::getId)
                        .last("LIMIT " + CANDIDATE_LIMIT))
                .stream().map(UserProfile::getUserId).toList();
    }

    public List<Long> match(CampaignDsl dsl, List<Long> candidates, LocalDateTime now) {
        if (dsl == null || dsl.getAudience() == null || dsl.getAudience().getConditions() == null
                || dsl.getAudience().getConditions().isEmpty()) {
            return List.of();
        }
        if (!"AND".equals(dsl.getAudience().getLogic()) || dsl.getAudience().getConditions().stream()
                .anyMatch(condition -> !fieldRegistry.isEnabled(condition.getField()))) {
            throw new IllegalArgumentException("only enabled recall fields combined with AND can be previewed");
        }
        List<CampaignRule> rules = converter.convert(dsl).stream()
                .map(rule -> CampaignRule.builder().ruleType(rule.ruleType()).ruleConfig(rule.ruleConfigJson()).build())
                .toList();
        List<Long> matched = new ArrayList<>();
        for (int offset = 0; offset < candidates.size(); offset += PAGE_SIZE) {
            List<Long> page = candidates.subList(offset, Math.min(offset + PAGE_SIZE, candidates.size()));
            matched.addAll(decisionEngine.matchScheduledAudience(page, rules, now));
        }
        return matched;
    }
}
