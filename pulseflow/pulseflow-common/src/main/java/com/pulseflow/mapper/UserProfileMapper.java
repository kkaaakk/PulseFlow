package com.pulseflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulseflow.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

/** UserProfile 的数据库映射接口，提供基础 CRUD 和所声明的业务查询。 */
@Mapper
@Repository
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}
