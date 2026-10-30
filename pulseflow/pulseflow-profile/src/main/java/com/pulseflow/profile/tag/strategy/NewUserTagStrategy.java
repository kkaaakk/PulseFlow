package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NEW_USER: Total event count < 10 in last 7 days
 */
@Component
public class NewUserTagStrategy implements TagStrategy {

    /**
     * 返回新用户代理标签代码 NEW_USER。
     */
    @Override
    public String getTagName() {
        return "NEW_USER";
    }

    /**
     * 以传入日桶总事件数少于十次近似新用户，返回 1 或 0；当前规则不读取注册日期。
     */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long total = metrics.stream()
                .mapToLong(m -> m.getEventCount() != null ? m.getEventCount() : 0)
                .sum();
        return total < 10 ? "1" : "0";
    }
}
