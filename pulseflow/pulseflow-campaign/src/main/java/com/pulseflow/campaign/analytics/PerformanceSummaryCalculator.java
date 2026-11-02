package com.pulseflow.campaign.analytics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummary;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummaryMapper;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.DeliveryRecord;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.AttributionRecordMapper;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.ClickEventMapper;
import com.pulseflow.mapper.DeliveryRecordMapper;
import com.pulseflow.mapper.DeliveryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes campaign performance metrics from raw event tables.
 *
 * <p>Per design §10.2 / §10.5: core rates are computed by the Java backend,
 * by Java. The output is upserted into campaign_performance_summary
 * (UK on campaign_id).</p>
 *
 * <p>v1 simplifications:</p>
 * <ul>
 *   <li>historicalAverageClickRate / conversionRate computed across all
 *       finished campaigns other than the current one (a coarse baseline).</li>
 *   <li>variantMetrics: not directly available in v1 schema — left as an
 *       empty JSON array because no variant-level facts are stored.</li>
 *   <li>unsubscribeCount: not tracked in current schema → 0.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceSummaryCalculator {

    private final CampaignMapper campaignMapper;
    private final DeliveryTaskMapper deliveryTaskMapper;
    private final DeliveryRecordMapper deliveryRecordMapper;
    private final ClickEventMapper clickEventMapper;
    private final AttributionRecordMapper attributionRecordMapper;
    private final CampaignPerformanceSummaryMapper summaryMapper;

    /**
     * Recompute a consistent snapshot. Serialize calculators per campaign so a
     * slower, older snapshot cannot overwrite a newer calculation.
     */
    @Transactional(rollbackFor = Exception.class)
    public CampaignPerformanceSummary compute(Long campaignId) {
        Campaign campaign = campaignMapper.selectForSummaryUpdate(campaignId);
        if (campaign == null) {
            throw new IllegalArgumentException("Campaign not found: " + campaignId);
        }
        CampaignPerformanceSummary summary = calculate(campaignId);
        summaryMapper.upsert(summary);
        return summary;
    }

    /** Fresh facts for read-only Web/Agent tools; never writes or acquires a write lock. */
    @Transactional(readOnly = true)
    public CampaignPerformanceSummary readCurrent(Long campaignId) {
        if (campaignMapper.selectById(campaignId) == null) {
            throw new IllegalArgumentException("Campaign not found: " + campaignId);
        }
        return calculate(campaignId);
    }

    private CampaignPerformanceSummary calculate(Long campaignId) {
        // 1. Delivery tasks for this campaign
        List<DeliveryTask> tasks = deliveryTaskMapper.selectList(
                new LambdaQueryWrapper<DeliveryTask>()
                        .eq(DeliveryTask::getCampaignId, campaignId));
        Set<Long> taskIds = new HashSet<>();
        for (DeliveryTask t : tasks) taskIds.add(t.getId());
        long targetAudienceCount = tasks.stream()
                .map(DeliveryTask::getUserId).distinct().count();

        // 2. Delivery records (sent / delivered)
        List<DeliveryRecord> deliveries = taskIds.isEmpty()
                ? List.of()
                : deliveryRecordMapper.selectList(
                        new LambdaQueryWrapper<DeliveryRecord>()
                                .eq(DeliveryRecord::getCampaignId, campaignId));
        long sentCount = deliveries.size();
        long deliveredCount = deliveries.stream()
                .filter(d -> "SENT".equalsIgnoreCase(d.getStatus())
                        || "DELIVERED".equalsIgnoreCase(d.getStatus()))
                .count();

        // 3. Click events (via task_id → delivery_task)
        long clickedCount = clickEventMapper.countValidUsers(campaignId);

        // 4. Attribution records (converted)
        long convertedCount = attributionRecordMapper.countValidConverters(campaignId);

        // 5. Unsubscribe — not tracked in v1
        long unsubscribeCount = 0;

        // 6. Rates (BigDecimal, scale=4)
        BigDecimal deliveryRate = rate(deliveredCount, sentCount);
        BigDecimal clickRate = rate(clickedCount, deliveredCount);
        BigDecimal conversionRate = rate(convertedCount, clickedCount);
        BigDecimal unsubscribeRate = rate(unsubscribeCount, sentCount);

        // 7. Historical baseline — coarse: average across all other campaigns
        Map<String, BigDecimal> baseline = computeHistoricalBaseline(campaignId);

        // 8. Variant metrics — empty array in v1
        List<Map<String, Object>> variantMetrics = new ArrayList<>();

        CampaignPerformanceSummary summary = CampaignPerformanceSummary.builder()
                .campaignId(campaignId)
                .targetAudienceCount(targetAudienceCount)
                .sentCount(sentCount)
                .deliveredCount(deliveredCount)
                .clickedCount(clickedCount)
                .convertedCount(convertedCount)
                .unsubscribeCount(unsubscribeCount)
                .deliveryRate(deliveryRate)
                .clickRate(clickRate)
                .conversionRate(conversionRate)
                .unsubscribeRate(unsubscribeRate)
                .baselineJson(com.pulseflow.common.util.JsonUtil.toJson(baseline))
                .variantMetricsJson(com.pulseflow.common.util.JsonUtil.toJson(variantMetrics))
                .calculatedAt(LocalDateTime.now())
                .build();
        return summary;
    }

    /**
     * Coarse historical baseline: average click_rate and conversion_rate
     * across all campaigns that already have a performance summary.
     */
    private Map<String, BigDecimal> computeHistoricalBaseline(Long excludeCampaignId) {
        List<CampaignPerformanceSummary> all = summaryMapper.selectList(
                new LambdaQueryWrapper<CampaignPerformanceSummary>()
                        .ne(CampaignPerformanceSummary::getCampaignId, excludeCampaignId));
        Map<String, BigDecimal> out = new HashMap<>();
        if (all.isEmpty()) {
            out.put("clickRate", BigDecimal.ZERO);
            out.put("conversionRate", BigDecimal.ZERO);
            return out;
        }
        BigDecimal clickSum = BigDecimal.ZERO;
        BigDecimal convSum = BigDecimal.ZERO;
        for (CampaignPerformanceSummary s : all) {
            clickSum = clickSum.add(s.getClickRate() == null ? BigDecimal.ZERO : s.getClickRate());
            convSum = convSum.add(s.getConversionRate() == null ? BigDecimal.ZERO : s.getConversionRate());
        }
        out.put("clickRate", clickSum.divide(BigDecimal.valueOf(all.size()), 4, RoundingMode.HALF_UP));
        out.put("conversionRate", convSum.divide(BigDecimal.valueOf(all.size()), 4, RoundingMode.HALF_UP));
        return out;
    }

    /** Shared authoritative rate semantics for summaries and period aggregates. */
    public static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
