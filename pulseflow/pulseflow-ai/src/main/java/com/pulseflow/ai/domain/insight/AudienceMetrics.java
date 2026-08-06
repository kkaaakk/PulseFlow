package com.pulseflow.ai.domain.insight;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Aggregated audience metrics computed by the Java backend before sending
 * to the LLM. The model only interprets these numbers — never recomputes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AudienceMetrics {

    private long audienceCount;

    /** 0.0–1.0 ratios or counts; null when not computable. */
    private BigDecimal activeRate7d;
    private BigDecimal averageSpend30d;
    private BigDecimal averageOrderCount30d;
    private BigDecimal cartWithoutPurchaseRate;
    private BigDecimal highValueRate;
    private BigDecimal priceSensitiveRate;
    private BigDecimal churnRiskRate;

    /** Top categories with rates, e.g. [{name:"数码", rate:0.38}]. */
    private List<Map<String, Object>> topCategories;

    /** member_level → ratio. */
    private Map<String, BigDecimal> memberLevelDistribution;

    /** Site-wide baseline. */
    private Map<String, BigDecimal> baseline;
}
