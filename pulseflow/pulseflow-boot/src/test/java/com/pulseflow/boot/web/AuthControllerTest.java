package com.pulseflow.boot.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    @Test
    void invalidCredentialsReturnUnauthorizedApiResponse() {
        AuthController controller = new AuthController(new LocalAuthProperties());

        var response = controller.login(new AuthController.LoginRequest(9999L, "wrong"));

        assertThat(response.getCode()).isEqualTo(401);
        assertThat(response.getData()).isNull();
    }
}
