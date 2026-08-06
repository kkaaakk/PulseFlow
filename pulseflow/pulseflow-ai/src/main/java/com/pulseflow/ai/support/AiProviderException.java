package com.pulseflow.ai.support;

import com.pulseflow.common.exception.PulseFlowException;

/**
 * Thrown when the upstream LLM provider fails (timeout / 5xx / rate limit).
 * Carries the stable {@code AI_PROVIDER_*} code.
 */
public class AiProviderException extends PulseFlowException {
    public AiProviderException(String errorCode, String message) {
        super(errorCode, message);
    }

    public AiProviderException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
