package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public interface UserProfileMapper extends BaseMapper<UserProfile> {
    @Select("SELECT MAX(id) FROM user_profile")
    Long selectHighWatermark();
}
