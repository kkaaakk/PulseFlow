package com.pulseflow.boot.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
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

    /**
     * 在本地登录启用时匹配配置的操作员或管理员账号密码；凭据缺失或不匹配时返回 null。
     */
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

    /**
     * 将登录 ID 转换为展示身份；配置账号保留其角色，其他有效 ID 使用默认操作员身份。
     */
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

    /**
     * 比较提交密码与配置密码的 UTF-8 字节，使用 MessageDigest.isEqual；未配置密码时直接失败。
     */
    private boolean matches(String presented, String configured) {
        if (configured == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));
    }

    public record AuthenticatedOperator(Long id, String role, String displayName) {
    }
}
