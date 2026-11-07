package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.InAppMessage;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface InAppMessageMapper extends BaseMapper<InAppMessage> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM in_app_message WHERE business_key=#{taskId}")
    InAppMessage selectByTaskId(@org.apache.ibatis.annotations.Param("taskId") Long taskId);
}
