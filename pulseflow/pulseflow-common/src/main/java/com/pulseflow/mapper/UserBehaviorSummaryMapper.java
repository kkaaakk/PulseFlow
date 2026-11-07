package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.UserBehaviorSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
@Repository
public interface UserBehaviorSummaryMapper extends BaseMapper<UserBehaviorSummary> {
    String INSERT_SNAPSHOT = """
            INSERT INTO user_behavior_summary
                (user_id, metric_type, metric_value, calculated_at, window_start, window_end)
            """;
    String UPDATE_SNAPSHOT = """
            ON DUPLICATE KEY UPDATE metric_value = VALUES(metric_value),
                window_start = VALUES(window_start), window_end = VALUES(window_end)
            """;

    @Insert(INSERT_SNAPSHOT + """
            SELECT active.user_id, 'active_days_7d', COUNT(DISTINCT active.day),
                   #{calculatedAt}, #{from}, #{until}
            FROM (
                SELECT user_id, metric_date AS day FROM user_metric_daily
                WHERE metric_date >= DATE(#{from}) AND metric_date < DATE(#{until})
                  AND event_type IN ('CONTENT_VIEW', 'ADD_CART') AND event_count > 0
                UNION ALL
                SELECT user_id, DATE(metric_hour) AS day FROM user_metric_hourly
                WHERE metric_hour >= #{from} AND metric_hour < #{until}
                  AND event_type IN ('CONTENT_VIEW', 'ADD_CART') AND event_count > 0
            ) active
            GROUP BY active.user_id
            """ + UPDATE_SNAPSHOT)
    int summarizeActiveDays(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until,
                            @Param("calculatedAt") LocalDateTime calculatedAt);

    @Insert(INSERT_SNAPSHOT + """
            SELECT e.user_id, 'order_count_30d',
                   COUNT(DISTINCT IF(e.paid_order_id IS NULL,
                       CONCAT('event:', e.event_id), CONCAT('order:', e.paid_order_id))),
                   #{calculatedAt}, #{from}, #{until}
            """ + UserEventMapper.PAID_WINDOW + """
            GROUP BY e.user_id
            """ + UPDATE_SNAPSHOT)
    int summarizePaidOrders(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until,
                            @Param("calculatedAt") LocalDateTime calculatedAt);

    @Insert(INSERT_SNAPSHOT + """
            SELECT user_id, 'spend_30d', SUM(amount_sum), #{calculatedAt}, #{from}, #{until}
            FROM user_metric_hourly
            WHERE metric_hour >= #{from} AND metric_hour < #{until} AND event_type = 'ORDER_PAID'
            GROUP BY user_id
            """ + UPDATE_SNAPSHOT)
    int summarizeSpend(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until,
                       @Param("calculatedAt") LocalDateTime calculatedAt);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM user_behavior_summary
                WHERE metric_type = 'active_days_7d' AND calculated_at >= #{today}
                  AND calculated_at <= #{asOf} AND window_end = #{until}
            ) OR (
                NOT EXISTS (
                    SELECT 1 FROM user_metric_hourly
                    WHERE metric_hour >= #{from} AND metric_hour < #{until}
                      AND event_type IN ('CONTENT_VIEW', 'ADD_CART') AND event_count > 0
                ) AND NOT EXISTS (
                    SELECT 1 FROM user_metric_daily
                    WHERE metric_date >= DATE(#{from}) AND metric_date < DATE(#{until})
                      AND event_type IN ('CONTENT_VIEW', 'ADD_CART') AND event_count > 0
                )
            )
            """)
    boolean readyForRecall(@Param("from") LocalDateTime from, @Param("today") LocalDateTime today,
                           @Param("asOf") LocalDateTime asOf, @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT user_id, metric_type, metric_value FROM (
                SELECT user_id, metric_type, metric_value,
                       ROW_NUMBER() OVER (PARTITION BY user_id, metric_type ORDER BY calculated_at DESC) AS rn
                FROM user_behavior_summary
                WHERE user_id IN
                <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  AND metric_type IN ('active_days_7d', 'order_count_30d', 'spend_30d')
                  AND calculated_at &gt;= #{today} AND calculated_at &lt;= #{asOf}
                  AND window_end = #{until}
            ) ranked WHERE rn = 1
            </script>
            """)
    List<UserBehaviorSummary> selectCurrentWindows(@Param("userIds") List<Long> userIds,
                                                   @Param("today") LocalDateTime today,
                                                   @Param("asOf") LocalDateTime asOf,
                                                   @Param("until") LocalDateTime until);
}
