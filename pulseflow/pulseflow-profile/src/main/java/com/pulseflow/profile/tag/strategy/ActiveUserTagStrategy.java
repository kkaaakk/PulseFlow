package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ACTIVE_USER: 7-day event count >= 50
 */
@Component
public class ActiveUserTagStrategy implements TagStrategy {

    /**
     * 返回活跃用户标签代码 ACTIVE_USER。
     */
    @Override
    public String getTagName() {
        return "ACTIVE_USER";
    }

    /**
     * 累计调用方传入日桶的全部事件数，达到五十次返回标签值 1，否则返回 0；时间范围由调用方控制。
     */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long total = metrics.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        return total >= 50 ? "1" : "0";
    }
}
