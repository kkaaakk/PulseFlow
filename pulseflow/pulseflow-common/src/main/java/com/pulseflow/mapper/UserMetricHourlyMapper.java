package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.UserMetricHourly;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** UserMetricHourly 的数据库映射接口，提供基础 CRUD 和所声明的业务查询。 */
@Mapper
@Repository
public interface UserMetricHourlyMapper extends BaseMapper<UserMetricHourly> {

    /**
     * 在单条 upsert 中累加小时桶事件数、时长和金额，避免并发读改写丢失统计。
     *
     * Atomic upsert for hourly metric bucket.
     * Splits event_count / duration_sum / amount_sum accumulation in a single statement,
     * avoiding the non-atomic select-then-update race condition.
     */
    @Insert("INSERT INTO user_metric_hourly (user_id, metric_hour, event_type, event_count, duration_sum, amount_sum) " +
            "VALUES (#{userId}, #{metricHour}, #{eventType}, #{eventCount}, #{durationSum}, #{amountSum}) " +
            "ON DUPLICATE KEY UPDATE " +
            "  event_count = event_count + VALUES(event_count), " +
            "  duration_sum = duration_sum + VALUES(duration_sum), " +
            "  amount_sum = amount_sum + VALUES(amount_sum)")
    int upsertAccumulate(@Param("userId") Long userId,
                         @Param("metricHour") LocalDateTime metricHour,
                         @Param("eventType") String eventType,
                         @Param("eventCount") int eventCount,
                         @Param("durationSum") long durationSum,
                         @Param("amountSum") BigDecimal amountSum);
}
