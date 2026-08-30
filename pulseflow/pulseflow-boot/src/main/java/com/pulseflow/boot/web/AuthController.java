package com.pulseflow.boot.web;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.common.model.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local web-console login backed by the existing Sa-Token session. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LocalAuthProperties authProperties;

    @PostMapping("/login")
    public ApiResponse<WebDtos.AuthSession> login(@Valid @RequestBody LoginRequest request) {
        LocalAuthProperties.AuthenticatedOperator operator =
                authProperties.authenticate(request.operatorId(), request.password());
        if (operator == null) {
            return ApiResponse.fail(401, "Invalid operator id or password");
        }

        StpUtil.login(operator.id());
        return ApiResponse.success(toSession(operator, StpUtil.getTokenValue()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<WebDtos.AuthSession> me() {
        Long loginId = currentLoginId();
        LocalAuthProperties.AuthenticatedOperator operator = authProperties.describe(loginId);
        if (operator == null) {
            return ApiResponse.fail(401, "Unauthorized");
        }
        return ApiResponse.success(toSession(operator, null));
    }

    private WebDtos.AuthSession toSession(LocalAuthProperties.AuthenticatedOperator operator,
                                          String tokenValue) {
        return new WebDtos.AuthSession(
                operator.id(),
                operator.role(),
                operator.displayName(),
                "token",
                tokenValue,
                String.valueOf(operator.id()));
    }

    private Long currentLoginId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? null : Long.valueOf(loginId.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    public record LoginRequest(
            @NotNull Long operatorId,
            @NotBlank String password
    ) {
    }
}
