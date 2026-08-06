package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LOYAL_CUSTOMER: Login on 5+ days in last 7 days
 */
@Component
public class LoyalCustomerTagStrategy implements TagStrategy {

    @Override
    public String getTagName() {
        return "LOYAL_CUSTOMER";
    }

    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long loginDays = metrics.stream()
                .filter(m -> "LOGIN".equals(m.getEventType()) && m.getEventCount() > 0)
                .count();
        return loginDays >= 5 ? "1" : "0";
    }
}
