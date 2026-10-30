package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CHURN_RISK: No LOGIN or CONTENT_VIEW events in last 7 days
 */
@Component
public class ChurnRiskTagStrategy implements TagStrategy {

    /**
     * 返回流失风险标签代码 CHURN_RISK。
     */
    @Override
    public String getTagName() {
        return "CHURN_RISK";
    }

    /**
     * 在传入日桶中没有 LOGIN 或 CONTENT_VIEW 类型记录时返回 1，否则返回 0；时间范围由调用方控制。
     */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        boolean hasRecentActivity = metrics.stream()
                .anyMatch(m -> "LOGIN".equals(m.getEventType())
                        || "CONTENT_VIEW".equals(m.getEventType()));
        return hasRecentActivity ? "0" : "1";
    }
}
