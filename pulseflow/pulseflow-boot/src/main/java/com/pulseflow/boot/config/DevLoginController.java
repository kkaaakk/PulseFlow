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
 * <b>DEMO / LOCAL ONLY</b> — issues a Sa-Token session for a given operator id
 * so the end-to-end AI Campaign Copilot demo script can call the
 * authenticated {@code /api/**} endpoints.
 *
 * <p>This controller is <b>off by default</b> and only activated by setting
 * {@code pulseflow.dev.demo-login-enabled=true}. It MUST NOT be enabled in any
 * real deployment: it lets any caller become any user without credentials.
 * The opt-in flag keeps production safe while making the demo runnable.</p>
 *
 * <p>See {@code docs/demo-scenario.md} for the full demo script.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/dev-login")
@ConditionalOnProperty(name = "pulseflow.dev.demo-login-enabled", havingValue = "true")
public class DevLoginController {

    /**
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

    /** Convenience GET form so the demo is runnable from a browser address bar. */
    @GetMapping
    public ApiResponse<Map<String, Object>> loginGet(@RequestParam(defaultValue = "1024") Long operatorId) {
        return login(operatorId);
    }

    @Data
    static class DevLoginResponse {
        private Long operatorId;
        private String tokenName;
        private String tokenValue;
    }
}
