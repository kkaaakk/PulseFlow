package com.pulseflow.boot.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 AuthController 的业务行为和边界条件。 */
class AuthControllerTest {

    /** 验证错误凭据返回统一未授权响应。 */
    @Test
    void invalidCredentialsReturnUnauthorizedApiResponse() {
        AuthController controller = new AuthController(new LocalAuthProperties());

        var response = controller.login(new AuthController.LoginRequest(9999L, "wrong"));

        assertThat(response.getCode()).isEqualTo(401);
        assertThat(response.getData()).isNull();
    }
}
