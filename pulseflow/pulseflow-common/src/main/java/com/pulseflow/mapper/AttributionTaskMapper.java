package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.AttributionTask;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface AttributionTaskMapper extends BaseMapper<AttributionTask> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM attribution_task WHERE target_event_id = #{eventId} FOR UPDATE")
    AttributionTask selectTargetForUpdate(@org.apache.ibatis.annotations.Param("eventId") String eventId);
}
