package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户标签计算策略：累计传入日桶的支付金额，达到五百元时返回 1，否则返回 0。
 *
 * HIGH_VALUE: 30-day spend >= 500 CNY
 */
@Component
public class HighValueTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "HIGH_VALUE";
    }

    /** 累计传入日桶的支付金额，达到五百元时返回 1，否则返回 0。 */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        BigDecimal total = metrics.stream()
                .filter(m -> "ORDER_PAID".equals(m.getEventType()))
                .map(UserMetricDaily::getAmountSum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.compareTo(new BigDecimal("500")) >= 0 ? "1" : "0";
    }
}
