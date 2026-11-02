package com.pulseflow.campaign.analytics;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummary;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface CampaignPerformanceSummaryMapper extends BaseMapper<CampaignPerformanceSummary> {
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO campaign_performance_summary
            (campaign_id,target_audience_count,sent_count,delivered_count,clicked_count,converted_count,
             unsubscribe_count,delivery_rate,click_rate,conversion_rate,unsubscribe_rate,
             baseline_json,variant_metrics_json,calculated_at)
            VALUES (#{campaignId},#{targetAudienceCount},#{sentCount},#{deliveredCount},#{clickedCount},
             #{convertedCount},#{unsubscribeCount},#{deliveryRate},#{clickRate},#{conversionRate},
             #{unsubscribeRate},#{baselineJson},#{variantMetricsJson},#{calculatedAt})
            ON DUPLICATE KEY UPDATE target_audience_count=VALUES(target_audience_count),
             sent_count=VALUES(sent_count),delivered_count=VALUES(delivered_count),
             clicked_count=VALUES(clicked_count),converted_count=VALUES(converted_count),
             unsubscribe_count=VALUES(unsubscribe_count),delivery_rate=VALUES(delivery_rate),
             click_rate=VALUES(click_rate),conversion_rate=VALUES(conversion_rate),
             unsubscribe_rate=VALUES(unsubscribe_rate),baseline_json=VALUES(baseline_json),
             variant_metrics_json=VALUES(variant_metrics_json),calculated_at=VALUES(calculated_at)
            """)
    int upsert(CampaignPerformanceSummary summary);
}
