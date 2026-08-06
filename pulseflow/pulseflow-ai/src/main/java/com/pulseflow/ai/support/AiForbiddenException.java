package com.pulseflow.ai.support;

/**
 * Thrown when the current operator does not own the target AI resource
 * (draft or campaign). Maps to HTTP 403.
 */
public class AiForbiddenException extends RuntimeException {
    public AiForbiddenException(String message) {
        super(message);
    }
}
