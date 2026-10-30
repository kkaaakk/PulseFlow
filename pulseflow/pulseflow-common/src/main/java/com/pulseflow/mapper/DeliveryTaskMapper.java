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

    /**
     * 仅将指定 ID 的 PENDING 任务更新为 PROCESSING 并写入处理时间；返回影响行数，一表示抢占成功。
     */
    @Update("UPDATE delivery_task SET status = 'PROCESSING', processing_at = NOW() WHERE id = #{id} AND status = 'PENDING'")
    int tryClaim(@Param("id") Long id);
}
