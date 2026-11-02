package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.Campaign;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface CampaignMapper extends BaseMapper<Campaign> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM campaign WHERE id = #{id} FOR UPDATE")
    Campaign selectForSummaryUpdate(@org.apache.ibatis.annotations.Param("id") Long id);
}
