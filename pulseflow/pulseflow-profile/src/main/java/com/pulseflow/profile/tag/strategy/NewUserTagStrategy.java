package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户标签计算策略：累计传入日桶事件数，小于十时返回 1，否则返回 0。
 *
 * NEW_USER: Total event count < 10 in last 7 days
 */
@Component
public class NewUserTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "NEW_USER";
    }

    /** 累计传入日桶事件数，小于十时返回 1，否则返回 0。 */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long total = metrics.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        return total < 10 ? "1" : "0";
    }
}
