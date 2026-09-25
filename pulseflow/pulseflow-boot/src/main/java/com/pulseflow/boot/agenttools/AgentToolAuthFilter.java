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

/** Separate machine credential; never consults browser sessions or operatorId. */
public final class AgentToolAuthFilter extends OncePerRequestFilter {
    static final String HEADER = "X-PulseFlow-Agent-Token";
    private final byte[] expectedHash;

    AgentToolAuthFilter(String token) {
        expectedHash = token == null || token.isBlank() ? null : hash(token);
    }

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

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
