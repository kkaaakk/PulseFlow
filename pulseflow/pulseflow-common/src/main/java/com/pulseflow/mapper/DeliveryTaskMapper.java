package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.DeliveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface DeliveryTaskMapper extends BaseMapper<DeliveryTask> {

    @Update("UPDATE delivery_task SET status = 'PROCESSING', processing_at = NOW() WHERE id = #{id} AND status = 'PENDING'")
    int tryClaim(@Param("id") Long id);
}
