package com.pulseflow.boot.config;

import cn.dev33.satoken.stp.StpUtil;
import com.pulseflow.common.model.ApiResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仅在演示环境提供按操作员 ID 建立会话的便捷入口。
 *
 * <b>DEMO / LOCAL ONLY</b> — issues a Sa-Token session for a given operator id
 * so the end-to-end AI Campaign Copilot demo script can call the
 * authenticated {@code /api/**} endpoints.
 *
 * <p>This controller is <b>off by default</b> and only activated by setting
 * {@code pulseflow.dev.demo-login-enabled=true}. It MUST NOT be enabled in any
 * real deployment: it lets any caller become any user without credentials.
 * The opt-in flag keeps production safe while making the demo runnable.</p>
 *
 * <p>See {@code ARCHITECTURE.md} for the system and authentication boundaries.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/dev-login")
@ConditionalOnProperty(name = "pulseflow.dev.demo-login-enabled", havingValue = "true")
public class DevLoginController {

    /**
     * 演示环境按操作员 ID 建立 Sa-Token 会话，返回后续请求所需的令牌。
     *
     * Login as a given operator id (demo only).
     * Usage: {@code POST /api/auth/dev-login?operatorId=1024}
     * Returns the Sa-Token value to send as the {@code token} header later.
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> login(@RequestParam(defaultValue = "1024") Long operatorId) {
        StpUtil.login(operatorId);
        String tokenValue = StpUtil.getTokenValue();
        log.warn("[DEMO LOGIN] issued token for operatorId={} (demo-login-enabled=true)", operatorId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operatorId", operatorId);
        data.put("tokenName", "token");
        data.put("tokenValue", tokenValue);
        data.put("loginId", StpUtil.getLoginIdAsString());
        return ApiResponse.success(data);
    }

    /**
     * 提供浏览器可直接访问的演示登录入口，复用同一登录逻辑。
     * Convenience GET form so the demo is runnable from a browser address bar. */
    @GetMapping
    public ApiResponse<Map<String, Object>> loginGet(@RequestParam(defaultValue = "1024") Long operatorId) {
        return login(operatorId);
    }

    /** 演示登录响应字段，保存操作员与令牌信息。 */
    @Data
    static class DevLoginResponse {
        private Long operatorId;
        private String tokenName;
        private String tokenValue;
    }
}
