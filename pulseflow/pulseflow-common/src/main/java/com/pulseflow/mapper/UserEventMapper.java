package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.UserEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface UserEventMapper extends BaseMapper<UserEvent> {
    @Select("SELECT * FROM user_event WHERE event_id = #{eventId} FOR UPDATE")
    UserEvent selectByEventIdForUpdate(@Param("eventId") String eventId);

    @Update("UPDATE user_event SET marketing_click_status = #{status}, marketing_click_reason = #{reason} "
            + "WHERE event_id = #{eventId}")
    int updateMarketingClick(@Param("eventId") String eventId, @Param("status") String status,
                             @Param("reason") String reason);

    @Select("SELECT COUNT(*) FROM user_event WHERE user_id = #{userId} AND event_type = 'CLICK' "
            + "AND marketing_click_status = 'PENDING' "
            + "AND effective_event_time >= #{from} AND effective_event_time < #{to}")
    long countPendingMarketingClicks(@Param("userId") Long userId,
                                    @Param("from") java.time.LocalDateTime from,
                                    @Param("to") java.time.LocalDateTime to);
}
