package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LOYAL_CUSTOMER: Login on 5+ days in last 7 days
 */
@Component
public class LoyalCustomerTagStrategy implements TagStrategy {

    /**
     * 返回忠诚用户标签代码 LOYAL_CUSTOMER。
     */
    @Override
    public String getTagName() {
        return "LOYAL_CUSTOMER";
    }

    /**
     * 统计存在正数登录计数的日桶记录，至少五条时返回 1，否则返回 0。
     */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long loginDays = metrics.stream()
                .filter(m -> "LOGIN".equals(m.getEventType()) && m.getEventCount() > 0)
                .count();
        return loginDays >= 5 ? "1" : "0";
    }
}
