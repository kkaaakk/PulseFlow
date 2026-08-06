package com.pulseflow.ai.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignPerformanceSummary;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface CampaignPerformanceSummaryMapper extends BaseMapper<CampaignPerformanceSummary> {
}
