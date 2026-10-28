package com.pulseflow.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 为实体插入及更新统一填充审计时间。
 *
 * MyBatis-Plus MetaObjectHandler for auto-filling {@code createdAt} and {@code updatedAt}
 * fields on insert and update operations.
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /** 插入实体时自动填充创建及更新时间，统一审计时间字段。 */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    /** 更新实体时自动填充修改时间，避免业务代码重复维护。 */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
