package com.pulseflow.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus MetaObjectHandler for auto-filling {@code createdAt} and {@code updatedAt}
 * fields on insert and update operations.
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入实体时按 MyBatis-Plus 严格填充策略补充 createdAt 与 updatedAt 时间。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 更新实体时按严格填充策略设置 updatedAt，保留其他业务字段。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
