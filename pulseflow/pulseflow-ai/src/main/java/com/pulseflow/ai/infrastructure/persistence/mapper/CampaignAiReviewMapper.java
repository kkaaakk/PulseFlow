package com.pulseflow.ai.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiReview;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface CampaignAiReviewMapper extends BaseMapper<CampaignAiReview> {
}
