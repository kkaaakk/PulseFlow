package com.pulseflow.campaign.analytics;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummary;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface CampaignPerformanceSummaryMapper extends BaseMapper<CampaignPerformanceSummary> {
}
