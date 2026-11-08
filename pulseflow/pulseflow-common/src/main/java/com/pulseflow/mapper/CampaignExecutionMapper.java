package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.CampaignExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Mapper
@Repository
public interface CampaignExecutionMapper extends BaseMapper<CampaignExecution> {
    @Select("""
            SELECT started_at FROM campaign_execution
            WHERE id = #{id} AND campaign_id = #{campaignId} AND status = 'RUNNING'
              AND retry_count = #{attempt}
            FOR UPDATE
            """)
    LocalDateTime lockAttemptStartedAt(@Param("id") Long id, @Param("campaignId") Long campaignId,
                                       @Param("attempt") Integer attempt);

    @Update("UPDATE campaign_execution SET started_at = #{now} WHERE id = #{id}")
    int renewLockedAttempt(@Param("id") Long id, @Param("now") LocalDateTime now);
}
