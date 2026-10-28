package com.pulseflow.boot.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 LocalAuthProperties 的业务行为和边界条件。 */
class LocalAuthPropertiesTest {

    /** 验证仅配置操作员可以认证，未知 ID 不被接受。 */
    @Test
    void authenticatesConfiguredOperatorWithoutAcceptingUnknownIds() {
        LocalAuthProperties properties = new LocalAuthProperties();

        assertThat(properties.authenticate(1024L, "pulseflow-local"))
                .extracting(LocalAuthProperties.AuthenticatedOperator::role)
                .isEqualTo("OPERATOR");
        assertThat(properties.authenticate(1024L, "wrong")).isNull();
        assertThat(properties.authenticate(9999L, "pulseflow-local")).isNull();
    }

    /** 验证管理员展示信息正确，且不包含密码。 */
    @Test
    void describesAdminAndDoesNotExposePassword() {
        LocalAuthProperties properties = new LocalAuthProperties();

        assertThat(properties.describe(1L))
                .extracting(LocalAuthProperties.AuthenticatedOperator::role)
                .isEqualTo("ADMIN");
        assertThat(properties.describe(1L).toString()).doesNotContain("pulseflow-admin");
    }
}
