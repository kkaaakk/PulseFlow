package com.pulseflow.job.handler;

import com.pulseflow.common.util.BehaviorWindow;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyMetricJob {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 将已完成小时桶汇总到日桶，重算更新聚合值，以便重复调度保持幂等。
     */
    @XxlJob("dailyMetricJob")
    public void execute() {
        log.info("DailyMetricJob started");

        LocalDateTime now = LocalDateTime.now();
        BehaviorWindow window = new BehaviorWindow(now.toLocalDate());
        String sql = """
            INSERT INTO user_metric_daily (user_id, metric_date, event_type, event_count, duration_sum, amount_sum)
            SELECT user_id, DATE(metric_hour), event_type,
                   SUM(event_count), SUM(duration_sum), SUM(amount_sum)
            FROM user_metric_hourly
            WHERE metric_hour >= ? AND metric_hour < ?
            GROUP BY DATE(metric_hour), user_id, event_type
            ON DUPLICATE KEY UPDATE
                event_count = VALUES(event_count),
                duration_sum = VALUES(duration_sum),
                amount_sum = VALUES(amount_sum)
            """;

        try {
            int updated = jdbcTemplate.update(sql, window.activityStart(), now.truncatedTo(ChronoUnit.HOURS));
            log.info("DailyMetricJob completed, rows affected: {}", updated);
        } catch (Exception e) {
            log.error("DailyMetricJob failed", e);
            throw e;
        }
    }
}
