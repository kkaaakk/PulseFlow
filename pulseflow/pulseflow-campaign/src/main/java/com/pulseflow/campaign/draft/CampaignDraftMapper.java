package com.pulseflow.campaign.draft;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.campaign.draft.CampaignDraft;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface CampaignDraftMapper extends BaseMapper<CampaignDraft> {
}
