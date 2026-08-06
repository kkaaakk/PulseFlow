package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * PRICE_SEN: Average order value < 50 CNY (price sensitive)
 */
@Component
public class PriceSensitiveTagStrategy implements TagStrategy {

    @Override
    public String getTagName() {
        return "PRICE_SEN";
    }

    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        List<UserMetricDaily> orders = metrics.stream()
                .filter(m -> "ORDER_PAID".equals(m.getEventType()))
                .toList();
        if (orders.isEmpty()) return "0";

        BigDecimal totalAmount = orders.stream()
                .map(UserMetricDaily::getAmountSum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long orderCount = orders.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();

        if (orderCount == 0) return "0";
        BigDecimal avgOrder = totalAmount.divide(BigDecimal.valueOf(orderCount), 2, BigDecimal.ROUND_HALF_UP);
        return avgOrder.compareTo(new BigDecimal("50")) < 0 ? "1" : "0";
    }
}
