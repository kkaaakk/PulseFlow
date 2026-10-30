package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI_PREF: Majority of viewed content is AI category (simplified - counts SEARCH with AI keyword)
 */
@Component
public class AiPreferenceTagStrategy implements TagStrategy {

    /**
     * 返回 AI 兴趣代理标签代码 AI_PREF。
     */
    @Override
    public String getTagName() {
        return "AI_PREF";
    }

    /**
     * 以搜索占总事件数大于百分之三十近似 AI 偏好，零事件返回 0；该规则不分析搜索文本。
     */
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
