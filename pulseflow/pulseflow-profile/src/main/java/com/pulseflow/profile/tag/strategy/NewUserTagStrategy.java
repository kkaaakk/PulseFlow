package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NEW_USER: Total event count < 10 in last 7 days
 */
@Component
public class NewUserTagStrategy implements TagStrategy {

    @Override
    public String getTagName() {
        return "NEW_USER";
    }

    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long total = metrics.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        return total < 10 ? "1" : "0";
    }
}
