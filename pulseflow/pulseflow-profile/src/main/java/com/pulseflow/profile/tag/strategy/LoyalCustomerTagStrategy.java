package com.pulseflow.profile.tag.strategy;

import com.pulseflow.entity.UserMetricDaily;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户标签计算策略：统计传入日桶中有登录行为的天数，达到五天返回 1，否则返回 0。
 *
 * LOYAL_CUSTOMER: Login on 5+ days in last 7 days
 */
@Component
public class LoyalCustomerTagStrategy implements TagStrategy {

    /** 返回该策略负责计算的标签代码。 */
    @Override
    public String getTagName() {
        return "LOYAL_CUSTOMER";
    }

    /** 统计传入日桶中有登录行为的天数，达到五天返回 1，否则返回 0。 */
    @Override
    public String compute(Long userId, List<UserMetricDaily> metrics) {
        long loginDays = metrics.stream()
                .filter(m -> "LOGIN".equals(m.getEventType()) && m.getEventCount() > 0)
                .count();
        return loginDays >= 5 ? "1" : "0";
    }
}
