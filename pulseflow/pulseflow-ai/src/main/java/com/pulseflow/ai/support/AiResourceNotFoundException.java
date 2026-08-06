package com.pulseflow.ai.support;

/**
 * Thrown when a referenced AI resource (draft, review, etc.) is not found.
 * Maps to HTTP 404.
 */
public class AiResourceNotFoundException extends RuntimeException {
    public AiResourceNotFoundException(String message) {
        super(message);
    }
}
