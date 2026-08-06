package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * HIGH_VALUE: 30-day spend >= 500 CNY
 */
@Component
public class HighValueTagStrategy implements TagStrategy {

    @Override
    public String getTagName() {
        return "HIGH_VALUE";
    }

    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        BigDecimal total = metrics.stream()
                .filter(m -> "ORDER_PAID".equals(m.getEventType()))
                .map(UserMetricDaily::getAmountSum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.compareTo(new BigDecimal("500")) >= 0 ? "1" : "0";
    }
}
