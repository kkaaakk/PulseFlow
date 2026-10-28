package com.pulseflow.boot.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 本地内存会话配置，使单节点登录会话独立于业务 Redis。
 *
 * Keeps the local single-node operator session independent from the business
 * Redis cache. This lets the User 360 MySQL fallback remain observable while
 * Redis is temporarily unavailable. Deployments that need distributed Sa-
 * Token sessions can set {@code PULSEFLOW_AUTH_SESSION_STORE=redis}.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "pulseflow.auth",
        name = "session-store",
        havingValue = "memory")
public class LocalAuthSessionConfig {

    /** 为本地认证提供会话存储实现，使登录会话按项目配置运行。 */
    @Bean
    @Primary
    public SaTokenDao localAuthSaTokenDao() {
        return new SaTokenDaoDefaultImpl();
    }
}
