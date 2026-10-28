package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户标签计算策略：以支付总额除以订单数计算客单价，低于五十元时返回 1，无订单时返回 0。
 *
 * PRICE_SEN: Average order value < 50 CNY (price sensitive)
 */
@Component
public class PriceSensitiveTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "PRICE_SEN";
    }

    /** 以支付总额除以订单数计算客单价，低于五十元时返回 1，无订单时返回 0。 */
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
