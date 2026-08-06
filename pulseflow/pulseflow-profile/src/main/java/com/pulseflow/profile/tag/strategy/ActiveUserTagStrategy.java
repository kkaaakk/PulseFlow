package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ACTIVE_USER: 7-day event count >= 50
 */
@Component
public class ActiveUserTagStrategy implements TagStrategy {

    @Override
    public String getTagName() {
        return "ACTIVE_USER";
    }

    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long total = metrics.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        return total >= 50 ? "1" : "0";
    }
}
