package com.pulseflow.event.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** A machine credential, never distributed to browsers or serialized into events. */
@Component
public class TrustedEventSource {
    public static final String HEADER = "X-PulseFlow-Event-Token";
    private final byte[] expected;

    public TrustedEventSource(@Value("${pulseflow.events.trusted-source-token:}") String token) {
        if (!token.isEmpty() && token.length() < 32) {
            throw new IllegalArgumentException("Event source token must contain at least 32 characters");
        }
        expected = token.getBytes(StandardCharsets.UTF_8);
    }

    public boolean authenticates(String supplied) {
        return expected.length > 0 && supplied != null && supplied.length() <= 512
                && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
    }
}
