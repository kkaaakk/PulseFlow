package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CHURN_RISK: No LOGIN or CONTENT_VIEW events in last 7 days
 */
@Component
public class ChurnRiskTagStrategy implements TagStrategy {

    @Override
    public String getTagName() {
        return "CHURN_RISK";
    }

    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        boolean hasRecentActivity = metrics.stream()
                .anyMatch(m -> "LOGIN".equals(m.getEventType())
                        || "CONTENT_VIEW".equals(m.getEventType()));
        return hasRecentActivity ? "0" : "1";
    }
}
