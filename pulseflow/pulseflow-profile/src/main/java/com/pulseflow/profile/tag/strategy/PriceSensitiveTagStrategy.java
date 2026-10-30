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

    /**
     * 返回价格敏感策略实际使用的标签代码 PRICE_SEN。
     */
    @Override
    public String getTagName() {
        return "PRICE_SEN";
    }

    /**
     * 按支付总额除以支付次数计算两位小数客单价，低于五十返回 1；无支付记录或次数为零返回 0。
     */
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
