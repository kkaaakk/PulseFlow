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

    /**
     * 使用本地操作员目录验证账号密码；成功后建立 Sa-Token 会话并返回令牌，失败返回业务码 401。
     */
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

    /**
     * 注销当前 Sa-Token 会话，返回无数据的成功响应。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.success();
    }

    /**
     * 读取当前会话的操作员展示信息；无有效登录时返回业务码 401，不重复下发令牌。
     */
    @GetMapping("/me")
    public ApiResponse<WebDtos.AuthSession> me() {
        Long loginId = currentLoginId();
        LocalAuthProperties.AuthenticatedOperator operator = authProperties.describe(loginId);
        if (operator == null) {
            return ApiResponse.fail(401, "Unauthorized");
        }
        return ApiResponse.success(toSession(operator, null));
    }

    /**
     * 把操作员身份与可选令牌转换为前端会话契约，统一声明 token 请求头名称。
     */
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

    /**
     * 尝试把 Sa-Token 登录标识转换为 Long；无会话或解析失败时返回 null。
     */
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
