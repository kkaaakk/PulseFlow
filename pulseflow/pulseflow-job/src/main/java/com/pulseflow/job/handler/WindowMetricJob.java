package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.entity.UserBehaviorSummary;
import com.pulseflow.entity.UserMetricDaily;
import com.pulseflow.entity.UserMetricHourly;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserMetricDailyMapper;
import com.pulseflow.mapper.UserMetricHourlyMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每小时聚合窗口指标写入 user_behavior_summary。
 *
 * <p>修复前只处理"当前小时桶有事件的用户"，导致历史活跃用户的窗口指标
 * （近7天活跃、近30天消费等）长期不刷新，决策引擎读到陈旧数据。
 * 现改为扫描近 30 天有日桶记录的全部用户，逐用户计算四类窗口指标。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WindowMetricJob {

    private final UserMetricDailyMapper metricDailyMapper;
    private final UserMetricHourlyMapper metricHourlyMapper;
    private final UserBehaviorSummaryMapper behaviorSummaryMapper;

    private static final int WINDOW_30D = 30;
    private static final int WINDOW_7D = 7;

    @XxlJob("windowMetricJob")
    public void execute() {
        log.info("WindowMetricJob started");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentHour = now.withMinute(0).withSecond(0).withNano(0);
        LocalDate since30d = now.toLocalDate().minusDays(WINDOW_30D);

        // 全量扫描近 30 天活跃用户（有日桶记录），而非仅当前小时有事件的用户。
        List<Long> userIds = metricDailyMapper.selectActiveUserIdsSince(since30d);
        if (userIds.isEmpty()) {
            log.info("WindowMetricJob: no active users in last 30d, skip");
            return;
        }

        int processed = 0;
        for (Long userId : userIds) {
            try {
                processUser(userId, now, currentHour);
                processed++;
            } catch (Exception e) {
                log.error("WindowMetric failed for user {}: {}", userId, e.getMessage(), e);
            }
        }

        log.info("WindowMetricJob completed, processed {} users", processed);
    }

    private void processUser(Long userId, LocalDateTime now, LocalDateTime currentHour) {
        // 读取该用户近 30 天全部日桶（一次性，减少查询次数）
        LocalDate since30d = now.toLocalDate().minusDays(WINDOW_30D);
        LocalDate since7d = now.toLocalDate().minusDays(WINDOW_7D);

        List<UserMetricDaily> daily30d = metricDailyMapper.selectList(
                new LambdaQueryWrapper<UserMetricDaily>()
                        .eq(UserMetricDaily::getUserId, userId)
                        .ge(UserMetricDaily::getMetricDate, since30d));

        // 读取当前小时桶（用于"近1小时"指标）
        List<UserMetricHourly> currentHourMetrics = metricHourlyMapper.selectList(
                new LambdaQueryWrapper<UserMetricHourly>()
                        .eq(UserMetricHourly::getUserId, userId)
                        .eq(UserMetricHourly::getMetricHour, currentHour));

        LocalDateTime calculatedAt = now;

        // search_1h: 当前小时桶的 SEARCH 计数
        computeAndSave(userId, "search_1h",
                sumHourlyEventCount(currentHourMetrics, "SEARCH"),
                calculatedAt, currentHour, currentHour.plusHours(1));

        // active_7d: 近 7 天 CONTENT_VIEW 计数
        List<UserMetricDaily> daily7d = daily30d.stream()
                .filter(m -> m.getMetricDate() != null
                        && !m.getMetricDate().isBefore(since7d))
                .collect(Collectors.toList());
        computeAndSave(userId, "active_7d",
                sumDailyEventCount(daily7d, "CONTENT_VIEW"),
                calculatedAt, since7d.atStartOfDay(), now);

        // spend_30d: 近 30 天 ORDER_PAID 金额累计
        computeAndSave(userId, "spend_30d",
                sumDailyAmount(daily30d, "ORDER_PAID"),
                calculatedAt, since30d.atStartOfDay(), now);

        // fav_7d: 近 7 天 FAVORITE 计数
        computeAndSave(userId, "fav_7d",
                sumDailyEventCount(daily7d, "FAVORITE"),
                calculatedAt, since7d.atStartOfDay(), now);
    }

    private void computeAndSave(Long userId, String metricType, BigDecimal value,
                                 LocalDateTime calculatedAt,
                                 LocalDateTime windowStart, LocalDateTime windowEnd) {
        UserBehaviorSummary summary = UserBehaviorSummary.builder()
                .userId(userId)
                .metricType(metricType)
                .metricValue(value)
                .calculatedAt(calculatedAt)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .build();
        behaviorSummaryMapper.insert(summary);
    }

    private BigDecimal sumHourlyEventCount(List<UserMetricHourly> metrics, String eventType) {
        return metrics.stream()
                .filter(m -> eventType.equals(m.getEventType()))
                .map(m -> BigDecimal.valueOf(m.getEventCount() != null ? m.getEventCount() : 0))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumDailyEventCount(List<UserMetricDaily> metrics, String eventType) {
        return metrics.stream()
                .filter(m -> eventType.equals(m.getEventType()))
                .map(m -> BigDecimal.valueOf(m.getEventCount() != null ? m.getEventCount() : 0))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumDailyAmount(List<UserMetricDaily> metrics, String eventType) {
        return metrics.stream()
                .filter(m -> eventType.equals(m.getEventType()))
                .map(m -> m.getAmountSum() != null ? m.getAmountSum() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
