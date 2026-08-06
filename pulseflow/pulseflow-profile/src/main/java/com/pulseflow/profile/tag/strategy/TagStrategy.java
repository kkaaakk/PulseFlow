package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;

import java.util.List;

/**
 * Strategy interface for computing long-term user tags.
 * Eight tag rules: AI_PREF, HIGH_VALUE, CHURN_RISK, PRICE_SEN,
 * ACTIVE_USER, NEW_USER, BARGAIN_HUNTER, LOYAL_CUSTOMER
 */
public interface TagStrategy {

    /** Tag name this strategy computes */
    String getTagName();

    /** Compute tag value for a user based on daily metrics */
    String compute(Long userId, List<UserMetricDaily> recentMetrics);

    /** Tag value if user doesn't meet criteria */
    default String defaultValue() {
        return "0";
    }
}
