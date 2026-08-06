package com.pulseflow.job.handler;

import com.pulseflow.mapper.UserMetricHourlyMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyMetricJob {

    private final JdbcTemplate jdbcTemplate;

    @XxlJob("dailyMetricJob")
    public void execute() {
        log.info("DailyMetricJob started");

        String sql = """
            INSERT INTO user_metric_daily (user_id, metric_date, event_type, event_count, duration_sum, amount_sum)
            SELECT user_id, DATE(metric_hour), event_type,
                   SUM(event_count), SUM(duration_sum), SUM(amount_sum)
            FROM user_metric_hourly
            WHERE metric_hour >= DATE_SUB(CURDATE(), INTERVAL 1 DAY)
              AND metric_hour < DATE_FORMAT(NOW(), '%Y-%m-%d %H:00:00')
            GROUP BY DATE(metric_hour), user_id, event_type
            ON DUPLICATE KEY UPDATE
                event_count = VALUES(event_count),
                duration_sum = VALUES(duration_sum),
                amount_sum = VALUES(amount_sum)
            """;

        try {
            int updated = jdbcTemplate.update(sql);
            log.info("DailyMetricJob completed, rows affected: {}", updated);
        } catch (Exception e) {
            log.error("DailyMetricJob failed", e);
            throw e;
        }
    }
}
