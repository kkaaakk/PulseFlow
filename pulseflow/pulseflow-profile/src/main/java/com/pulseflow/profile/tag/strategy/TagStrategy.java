package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;

import java.util.List;

/**
 * 用户标签计算策略：根据用户及近期日桶计算标签值，命中与未命中均返回可持久化字符串。
 *
 * Strategy interface for computing long-term user tags.
 * Eight tag rules: AI_PREF, HIGH_VALUE, CHURN_RISK, PRICE_SEN,
 * ACTIVE_USER, NEW_USER, BARGAIN_HUNTER, LOYAL_CUSTOMER
 */
public interface TagStrategy {

    /**
     * 返回策略负责的标签名，供重算任务保存和规则引擎查询。
     * Tag name this strategy computes */
    String getTagName();

    /**
     * 根据用户及近期日桶计算标签值，命中与未命中均返回可持久化字符串。
     * Compute tag value for a user based on daily metrics */
    String compute(Long userId, List<UserMetricDaily> recentMetrics);

    /**
     * 返回未满足标签条件时的默认值。
     * Tag value if user doesn't meet criteria */
    default String defaultValue() {
        return "0";
    }
}
