package com.pulseflow.ai.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pre-computed campaign performance metrics. UK on campaign_id.
 *
 * <p>These numbers are authoritative — AI review MUST NOT recompute them,
 * only interpret.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("campaign_performance_summary")
public class CampaignPerformanceSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campaignId;

    private Long targetAudienceCount;

    private Long sentCount;

    private Long deliveredCount;

    private Long clickedCount;

    private Long convertedCount;

    private Long unsubscribeCount;

    private BigDecimal deliveryRate;

    private BigDecimal clickRate;

    private BigDecimal conversionRate;

    private BigDecimal unsubscribeRate;

    /** Baseline + variant metrics JSON. */
    private String baselineJson;

    private String variantMetricsJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime calculatedAt;
}
