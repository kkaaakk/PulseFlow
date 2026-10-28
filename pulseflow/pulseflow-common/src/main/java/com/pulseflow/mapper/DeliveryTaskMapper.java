package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.DeliveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/** DeliveryTask 的数据库映射接口，提供基础 CRUD 和所声明的业务查询。 */
@Mapper
@Repository
public interface DeliveryTaskMapper extends BaseMapper<DeliveryTask> {

    /** 用条件更新原子领取触达任务，返回影响行数以判断本实例是否获得执行权。 */
    @Update("UPDATE delivery_task SET status = 'PROCESSING', processing_at = NOW() WHERE id = #{id} AND status = 'PENDING'")
    int tryClaim(@Param("id") Long id);
}
