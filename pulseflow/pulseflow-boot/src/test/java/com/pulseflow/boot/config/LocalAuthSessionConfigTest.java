package com.pulseflow.boot.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAuthSessionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LocalAuthSessionConfig.class);

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

    @Test
    void redisStoreDoesNotInstallLocalDao() {
        contextRunner
                .withPropertyValues("pulseflow.auth.session-store=redis")
                .run(context -> assertThat(context).doesNotHaveBean(SaTokenDao.class));
    }
}
