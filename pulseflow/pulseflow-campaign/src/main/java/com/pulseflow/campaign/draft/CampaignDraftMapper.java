package com.pulseflow.campaign.draft;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.campaign.draft.CampaignDraft;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

/** CampaignDraft 的数据库映射接口，提供基础 CRUD 和所声明的业务查询。 */
@Mapper
@Repository
public interface CampaignDraftMapper extends BaseMapper<CampaignDraft> {
}
