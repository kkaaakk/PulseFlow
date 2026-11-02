package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.DeliveryRecord;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface DeliveryRecordMapper extends BaseMapper<DeliveryRecord> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM delivery_record WHERE task_id = #{taskId}")
    DeliveryRecord selectByTaskId(@org.apache.ibatis.annotations.Param("taskId") Long taskId);
}
