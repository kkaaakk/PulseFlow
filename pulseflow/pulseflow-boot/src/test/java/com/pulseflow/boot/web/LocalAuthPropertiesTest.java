package com.pulseflow.boot.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAuthPropertiesTest {

    @Test
    void authenticatesConfiguredOperatorWithoutAcceptingUnknownIds() {
        LocalAuthProperties properties = new LocalAuthProperties();

        assertThat(properties.authenticate(1024L, "pulseflow-local"))
                .extracting(LocalAuthProperties.AuthenticatedOperator::role)
                .isEqualTo("OPERATOR");
        assertThat(properties.authenticate(1024L, "wrong")).isNull();
        assertThat(properties.authenticate(9999L, "pulseflow-local")).isNull();
    }

    @Test
    void describesAdminAndDoesNotExposePassword() {
        LocalAuthProperties properties = new LocalAuthProperties();

        assertThat(properties.describe(1L))
                .extracting(LocalAuthProperties.AuthenticatedOperator::role)
                .isEqualTo("ADMIN");
        assertThat(properties.describe(1L).toString()).doesNotContain("pulseflow-admin");
    }
}
