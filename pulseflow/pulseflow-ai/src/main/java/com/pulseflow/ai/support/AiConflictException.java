package com.pulseflow.ai.support;

/**
 * Thrown when an AI resource is in a state that conflicts with the requested
 * operation (e.g. confirming a non-VALIDATED draft, regenerating a review
 * that is currently PROCESSING). Maps to HTTP 409.
 */
public class AiConflictException extends RuntimeException {
    public AiConflictException(String message) {
        super(message);
    }
}
