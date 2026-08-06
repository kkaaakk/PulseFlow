package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.UserMetricDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Mapper
@Repository
public interface UserMetricDailyMapper extends BaseMapper<UserMetricDaily> {

    /**
     * 查询近 N 天有事件的去重 userId 列表，供 WindowMetricJob 全量刷新用。
     * 只扫描日桶避免全表扫历史数据。
     */
    @Select("SELECT DISTINCT user_id FROM user_metric_daily WHERE metric_date >= #{since}")
    List<Long> selectActiveUserIdsSince(@Param("since") LocalDate since);
}
