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

/**
 * 基于本地操作员配置和 Sa-Token 的控制台认证接口。
 * Local web-console login backed by the existing Sa-Token session. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LocalAuthProperties authProperties;

    /** 验证操作员凭据并建立会话，返回令牌与可展示的操作员信息。 */
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

    /** 注销当前 Sa-Token 会话，返回无数据的成功响应。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.success();
    }

    /** 读取当前登录身份及其展示信息，供前端恢复会话。 */
    @GetMapping("/me")
    public ApiResponse<WebDtos.AuthSession> me() {
        Long loginId = currentLoginId();
        LocalAuthProperties.AuthenticatedOperator operator = authProperties.describe(loginId);
        if (operator == null) {
            return ApiResponse.fail(401, "Unauthorized");
        }
        return ApiResponse.success(toSession(operator, null));
    }

    /** 将认证结果和令牌映射为前端会话 DTO，统一登录与会话查询返回结构。 */
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

    /** 从当前会话读取操作员 ID，无有效会话时触发认证异常。 */
    private Long currentLoginId() {
        try {
            Object loginId = StpUtil.getLoginId();
            return loginId == null ? null : Long.valueOf(loginId.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 控制台登录凭据，操作员 ID 与本地访问口令必须配套校验。 */
    public record LoginRequest(
            @NotNull Long operatorId,
            @NotBlank String password
    ) {
    }
}
