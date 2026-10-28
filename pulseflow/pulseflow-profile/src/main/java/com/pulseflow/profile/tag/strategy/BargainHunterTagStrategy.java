package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户标签计算策略：加购与支付比超过三时命中；无支付时要求加购数超过五。
 *
 * BARGAIN_HUNTER: High ADD_CART to ORDER_PAID ratio (> 3:1) indicates browsing without buying
 */
@Component
public class BargainHunterTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "BARGAIN_HUNTER";
    }

    /** 根据传入的近期日桶加购与支付比超过三时命中；无支付时要求加购数超过五。 */
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
