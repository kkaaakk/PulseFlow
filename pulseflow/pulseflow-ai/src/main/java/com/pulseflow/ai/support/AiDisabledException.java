package com.pulseflow.ai.support;

import com.pulseflow.common.exception.PulseFlowException;

/**
 * Thrown when AI feature is disabled ({@code pulseflow.ai.enabled=false}).
 * Mapped to HTTP 503 by GlobalExceptionHandler.
 */
public class AiDisabledException extends PulseFlowException {
    public AiDisabledException() {
        super(AiErrorCode.AI_DISABLED, "AI feature is disabled");
    }
}
