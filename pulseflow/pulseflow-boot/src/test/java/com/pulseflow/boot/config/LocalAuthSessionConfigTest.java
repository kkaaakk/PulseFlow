package com.pulseflow.boot.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 LocalAuthSessionConfig 的业务行为和边界条件。 */
class LocalAuthSessionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LocalAuthSessionConfig.class);

    /** 验证内存会话配置注册本地 Sa-Token 存储。 */
    @Test
    void memoryStoreProvidesLocalSaTokenDao() {
        contextRunner
                .withPropertyValues("pulseflow.auth.session-store=memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(SaTokenDao.class);
                    assertThat(context.getBean(SaTokenDao.class))
                            .isInstanceOf(SaTokenDaoDefaultImpl.class);
                });
    }

    /** 验证 Redis 会话配置不会意外覆盖为本地存储。 */
    @Test
    void redisStoreDoesNotInstallLocalDao() {
        contextRunner
                .withPropertyValues("pulseflow.auth.session-store=redis")
                .run(context -> assertThat(context).doesNotHaveBean(SaTokenDao.class));
    }
}
