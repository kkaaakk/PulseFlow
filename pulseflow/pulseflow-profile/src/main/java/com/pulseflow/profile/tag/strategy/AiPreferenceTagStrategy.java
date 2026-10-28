package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户标签计算策略：搜索事件占比大于三成时返回 1；这是基于行为比例的简化兴趣标签。
 *
 * AI_PREF: Majority of viewed content is AI category (simplified - counts SEARCH with AI keyword)
 */
@Component
public class AiPreferenceTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "AI_PREF";
    }

    /** 根据传入的近期日桶搜索事件占比大于三成时返回 1；这是基于行为比例的简化兴趣标签。 */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long searchCount = metrics.stream()
                .filter(m -> "SEARCH".equals(m.getEventType()))
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        // Simplified: assume 30%+ search ratio indicates AI interest
        long totalEvents = metrics.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        if (totalEvents == 0) return "0";
        return (double) searchCount / totalEvents > 0.3 ? "1" : "0";
    }
}
