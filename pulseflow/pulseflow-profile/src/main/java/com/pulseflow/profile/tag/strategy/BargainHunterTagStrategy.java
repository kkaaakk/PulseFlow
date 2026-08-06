package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BARGAIN_HUNTER: High ADD_CART to ORDER_PAID ratio (> 3:1) indicates browsing without buying
 */
@Component
public class BargainHunterTagStrategy implements TagStrategy {

    @Override
    public String getTagName() {
        return "BARGAIN_HUNTER";
    }

    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long addCart = metrics.stream()
                .filter(m -> "ADD_CART".equals(m.getEventType()))
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        long paid = metrics.stream()
                .filter(m -> "ORDER_PAID".equals(m.getEventType()))
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        if (paid == 0) return addCart > 5 ? "1" : "0";
        return (double) addCart / paid > 3.0 ? "1" : "0";
    }
}
