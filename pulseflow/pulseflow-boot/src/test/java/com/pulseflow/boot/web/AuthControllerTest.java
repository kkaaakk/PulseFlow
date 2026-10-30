package com.pulseflow.boot.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    /**
     * 验证错误登录凭据返回统一业务码 401 响应。
     */
    @Test
    void invalidCredentialsReturnUnauthorizedApiResponse() {
        AuthController controller = new AuthController(new LocalAuthProperties());

        var response = controller.login(new AuthController.LoginRequest(9999L, "wrong"));

        assertThat(response.getCode()).isEqualTo(401);
        assertThat(response.getData()).isNull();
    }
}
