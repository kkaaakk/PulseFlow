package com.pulseflow.campaign.analytics;

import com.pulseflow.campaign.dsl.CampaignDsl;
import com.pulseflow.campaign.preview.SqlAudiencePreviewService;
import com.pulseflow.common.util.BehaviorWindow;
import com.pulseflow.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Aggregate the same SCHEDULED audience as the preview, without exposing user IDs. */
@Service
@RequiredArgsConstructor
public class AudienceMetricsAggregator {

    private static final int PAGE_SIZE = 500;

    private final SqlAudiencePreviewService preview;
    private final ProfileService profileService;

    public AudienceMetrics aggregate(CampaignDsl dsl) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> candidates = preview.enabledCandidateIds();
        List<Long> matched = preview.match(dsl, candidates, now);
        if (matched.isEmpty()) return AudienceMetrics.builder().audienceCount(0).build();

        Totals audience = summarize(matched, now);
        Totals baseline = summarize(candidates, now);
        return AudienceMetrics.builder()
                .audienceCount(matched.size())
                .activeRate7d(average(BigDecimal.valueOf(audience.active()), matched.size(), 4))
                .averageSpend30d(average(audience.spend(), matched.size(), 2))
                .averageOrderCount30d(average(audience.orders(), matched.size(), 2))
                .baseline(Map.of(
                        "activeRate7d", average(BigDecimal.valueOf(baseline.active()), candidates.size(), 4),
                        "averageSpend30d", average(baseline.spend(), candidates.size(), 2),
                        "averageOrderCount30d", average(baseline.orders(), candidates.size(), 2)))
                .build();
    }

    private Totals summarize(List<Long> userIds, LocalDateTime now) {
        int active = 0;
        BigDecimal orders = BigDecimal.ZERO;
        BigDecimal spend = BigDecimal.ZERO;
        for (int offset = 0; offset < userIds.size(); offset += PAGE_SIZE) {
            List<Long> page = userIds.subList(offset, Math.min(offset + PAGE_SIZE, userIds.size()));
            for (var metrics : profileService.readCurrentWindowMetrics(page, now).values()) {
                if (metrics.getOrDefault(BehaviorWindow.ACTIVE_DAYS, BigDecimal.ZERO).signum() > 0) active++;
                orders = orders.add(metrics.getOrDefault(BehaviorWindow.ORDER_COUNT, BigDecimal.ZERO));
                spend = spend.add(metrics.getOrDefault(BehaviorWindow.SPEND, BigDecimal.ZERO));
            }
        }
        return new Totals(active, orders, spend);
    }

    private BigDecimal average(BigDecimal total, int size, int scale) {
        return total.divide(BigDecimal.valueOf(size), scale, RoundingMode.HALF_UP);
    }

    private record Totals(int active, BigDecimal orders, BigDecimal spend) {}
}
