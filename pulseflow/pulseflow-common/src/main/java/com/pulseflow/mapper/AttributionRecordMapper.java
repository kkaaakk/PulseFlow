package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.AttributionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface AttributionRecordMapper extends BaseMapper<AttributionRecord> {
    String VALID_FROM = ClickEventMapper.VALID_FROM
            + "JOIN attribution_record a ON a.click_event_id = c.id AND a.user_id = c.user_id "
            + "AND a.task_id = c.task_id AND a.campaign_id = t.campaign_id ";

    @org.apache.ibatis.annotations.Select("SELECT COUNT(DISTINCT a.user_id)" + VALID_FROM
            + "WHERE" + ClickEventMapper.VALID_WHERE + "AND a.campaign_id = #{campaignId}")
    long countValidConverters(@org.apache.ibatis.annotations.Param("campaignId") Long campaignId);
}
