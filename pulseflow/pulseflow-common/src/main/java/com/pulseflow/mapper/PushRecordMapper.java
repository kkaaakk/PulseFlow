package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.PushRecord;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface PushRecordMapper extends BaseMapper<PushRecord> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM push_record WHERE business_key=#{taskId}")
    PushRecord selectByTaskId(@org.apache.ibatis.annotations.Param("taskId") Long taskId);
}
