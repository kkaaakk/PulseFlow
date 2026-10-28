package com.pulseflow.boot.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 本地操作员目录和认证配置，供首版控制台登录使用。
 *
 * Minimal local operator directory for the first web-console release.
 *
 * <p>There is deliberately no new user/RBAC table in v1. Credentials are
 * supplied through environment-backed properties and the authenticated id is
 * still recorded by Sa-Token. Production deployments should replace this
 * small local directory with the organization's identity provider.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pulseflow.auth")
public class LocalAuthProperties {

    private boolean localEnabled = true;
    private Long operatorId = 1024L;
    private String operatorPassword = "pulseflow-local";
    private String operatorName = "Operator";
    private Long adminId = 1L;
    private String adminPassword = "pulseflow-admin";
    private String adminName = "Administrator";

    /** 按配置校验操作员 ID 与密码，认证通过后返回操作员描述。 */
    public AuthenticatedOperator authenticate(Long requestedId, String password) {
        if (!localEnabled || requestedId == null || password == null) {
            return null;
        }
        if (requestedId.equals(operatorId) && matches(password, operatorPassword)) {
            return new AuthenticatedOperator(operatorId, "OPERATOR", operatorName);
        }
        if (requestedId.equals(adminId) && matches(password, adminPassword)) {
            return new AuthenticatedOperator(adminId, "ADMIN", adminName);
        }
        return null;
    }

    /** 根据登录 ID 构造操作员展示信息，供已认证会话查询使用。 */
    public AuthenticatedOperator describe(Long loginId) {
        if (loginId == null) {
            return null;
        }
        if (loginId.equals(operatorId)) {
            return new AuthenticatedOperator(operatorId, "OPERATOR", operatorName);
        }
        if (loginId.equals(adminId)) {
            return new AuthenticatedOperator(adminId, "ADMIN", adminName);
        }
        return new AuthenticatedOperator(loginId, "OPERATOR", "Operator " + loginId);
    }

    /** 安全比较提交值与配置凭据，避免普通字符串比较泄露匹配时序。 */
    private boolean matches(String presented, String configured) {
        if (configured == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));
    }

    /** 认证通过后的操作员描述，用于构造会话信息。 */
    public record AuthenticatedOperator(Long id, String role, String displayName) {
    }
}
