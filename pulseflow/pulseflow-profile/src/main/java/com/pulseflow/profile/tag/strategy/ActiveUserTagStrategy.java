package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户标签计算策略：累计事件数达到五十时返回 1，否则返回 0。
 *
 * ACTIVE_USER: 7-day event count >= 50
 */
@Component
public class ActiveUserTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "ACTIVE_USER";
    }

    /** 根据传入的近期日桶累计事件数达到五十时返回 1，否则返回 0。 */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long total = metrics.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        return total >= 50 ? "1" : "0";
    }
}
