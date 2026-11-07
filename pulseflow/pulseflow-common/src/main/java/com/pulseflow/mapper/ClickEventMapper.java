package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.ClickEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface ClickEventMapper extends BaseMapper<ClickEvent> {
    // One validity definition for Last-Touch, Campaign, Web and Agent queries.
    // The task is the sole authoritative send fact; legacy clicks still require provenance.
    String VALID_FROM = " FROM click_event c JOIN delivery_task t ON t.id = c.task_id "
            + "JOIN user_event e ON e.event_id = c.source_event_id ";
    String VALID_WHERE = " e.marketing_trusted = 1 AND e.marketing_click_status = 'VALID' "
            + "AND e.event_type = 'CLICK' AND e.user_id = c.user_id AND e.effective_event_time = c.click_time "
            + "AND t.user_id = c.user_id AND t.status = 'SENT' "
            + "AND t.sent_at IS NOT NULL AND t.sent_at <= c.click_time ";


    @Insert("INSERT INTO click_event (source_event_id,user_id,task_id,click_time,click_source,properties) "
            + "VALUES (#{sourceEventId},#{userId},#{taskId},#{clickTime},#{clickSource},#{properties}) "
            + "ON DUPLICATE KEY UPDATE source_event_id = VALUES(source_event_id)")
    int insertCanonical(ClickEvent click);

    @Select("SELECT c.*" + VALID_FROM + "WHERE" + VALID_WHERE
            + "AND c.user_id = #{userId} AND c.click_time >= #{from} AND c.click_time < #{to} "
            + "ORDER BY c.click_time DESC, c.id DESC LIMIT 1")
    ClickEvent selectLastValid(@Param("userId") Long userId,
                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(DISTINCT c.user_id)" + VALID_FROM + "WHERE" + VALID_WHERE
            + "AND t.campaign_id = #{campaignId}")
    long countValidUsers(@Param("campaignId") Long campaignId);

    @Select("SELECT c.*" + VALID_FROM + "WHERE" + VALID_WHERE
            + "AND c.task_id = #{taskId} ORDER BY c.click_time DESC, c.id DESC")
    List<ClickEvent> selectValidByTask(@Param("taskId") Long taskId);
}
