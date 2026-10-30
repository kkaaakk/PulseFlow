package com.pulseflow.boot.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAuthPropertiesTest {

    /**
     * 验证仅配置的账号密码可通过认证，未知 ID 被拒绝。
     */
    @Test
    void authenticatesConfiguredOperatorWithoutAcceptingUnknownIds() {
        LocalAuthProperties properties = new LocalAuthProperties();

        assertThat(properties.authenticate(1024L, "pulseflow-local"))
                .extracting(LocalAuthProperties.AuthenticatedOperator::role)
                .isEqualTo("OPERATOR");
        assertThat(properties.authenticate(1024L, "wrong")).isNull();
        assertThat(properties.authenticate(9999L, "pulseflow-local")).isNull();
    }

    /**
     * 验证管理员身份展示正确且不包含密码。
     */
    @Test
    void describesAdminAndDoesNotExposePassword() {
        LocalAuthProperties properties = new LocalAuthProperties();

        assertThat(properties.describe(1L))
                .extracting(LocalAuthProperties.AuthenticatedOperator::role)
                .isEqualTo("ADMIN");
        assertThat(properties.describe(1L).toString()).doesNotContain("pulseflow-admin");
    }
}
