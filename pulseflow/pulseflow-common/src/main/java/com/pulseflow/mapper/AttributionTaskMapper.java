package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.AttributionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface AttributionTaskMapper extends BaseMapper<AttributionTask> {
    @Select("SELECT * FROM attribution_task WHERE target_event_id = #{eventId} FOR UPDATE")
    AttributionTask selectTargetForUpdate(@Param("eventId") String eventId);

    @Select("SELECT * FROM attribution_task WHERE user_id = #{userId} AND paid_order_id = #{orderId} FOR UPDATE")
    AttributionTask selectOrderForUpdate(@Param("userId") Long userId, @Param("orderId") String orderId);

    /** A legacy event may already have a task or a retained result, even before the order key existed. */
    @Select("""
            SELECT e.event_id
            FROM user_event e
            LEFT JOIN attribution_task task ON task.target_event_id = e.event_id
            LEFT JOIN attribution_record result ON result.target_event_id = e.event_id
            WHERE e.user_id = #{userId} AND e.paid_order_id = #{orderId}
              AND (task.id IS NOT NULL OR result.id IS NOT NULL)
            ORDER BY e.id
            LIMIT 1
            """)
    String selectExistingOrderTarget(@Param("userId") Long userId, @Param("orderId") String orderId);
}
