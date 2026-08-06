package com.pulseflow.ai.domain.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A single real promotion fact supplied to AI.
 *
 * <p>AI may NOT invent new promotion facts; the ContentFactValidator rejects
 * content that references numbers not present here.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromotionFact {

    /** FULL_REDUCTION / DISCOUNT / COUPON / GIFT / ... */
    private String type;

    private BigDecimal threshold;

    private BigDecimal discount;

    /** Optional rate, e.g. 0.08 for 8 折. */
    private BigDecimal rate;

    /** Optional deadline, ISO-8601 date or datetime. */
    private String validUntil;

    /** Free-form description for AI prompt context. */
    private String description;
}
