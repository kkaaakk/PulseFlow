package com.pulseflow.boot.agenttools;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 使用独立机器令牌保护聚合工具接口，与浏览器会话隔离。
 * Separate machine credential; never consults browser sessions or operatorId. */
public final class AgentToolAuthFilter extends OncePerRequestFilter {
    static final String HEADER = "X-PulseFlow-Agent-Token";
    private final byte[] expectedHash;

    /** 预处理配置令牌，供后续请求做固定格式的安全比较。 */
    AgentToolAuthFilter(String token) {
        expectedHash = token == null || token.isBlank() ? null : hash(token);
    }

    /** 校验内部机器令牌，未通过时直接返回未授权响应，通过才继续过滤链。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (expectedHash == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Internal API unavailable");
            return;
        }
        String supplied = request.getHeader(HEADER);
        if (supplied == null || supplied.isBlank() || supplied.length() > 512
                || !MessageDigest.isEqual(expectedHash, hash(supplied))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 计算令牌摘要，用于避免直接比较明文长度和内容。 */
    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
