package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户标签计算策略：近期没有登录或浏览行为时返回 1，表示存在流失风险。
 *
 * CHURN_RISK: No LOGIN or CONTENT_VIEW events in last 7 days
 */
@Component
public class ChurnRiskTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "CHURN_RISK";
    }

    /** 根据传入的近期日桶近期没有登录或浏览行为时返回 1，表示存在流失风险。 */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        boolean hasRecentActivity = metrics.stream()
                .anyMatch(m -> "LOGIN".equals(m.getEventType())
                        || "CONTENT_VIEW".equals(m.getEventType()));
        return hasRecentActivity ? "0" : "1";
    }
}
