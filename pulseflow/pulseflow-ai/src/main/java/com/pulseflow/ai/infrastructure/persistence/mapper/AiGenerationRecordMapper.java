package com.pulseflow.ai.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.ai.infrastructure.persistence.entity.AiGenerationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface AiGenerationRecordMapper extends BaseMapper<AiGenerationRecord> {
}
