package com.pulseflow.campaign.analytics;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.campaign.analytics.CampaignPerformanceSummary;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

/** CampaignPerformanceSummary 的数据库映射接口，提供基础 CRUD 和所声明的业务查询。 */
@Mapper
@Repository
public interface CampaignPerformanceSummaryMapper extends BaseMapper<CampaignPerformanceSummary> {
}
