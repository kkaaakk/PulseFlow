package com.pulseflow.ai.support;

import com.pulseflow.common.exception.PulseFlowException;

/**
 * Indicates that the PII safety check could not complete. Callers must not
 * continue to an LLM when this exception is raised (fail closed).
 */
public class AiPiiGuardrailUnavailableException extends PulseFlowException {

    public AiPiiGuardrailUnavailableException(String message) {
        super(AiErrorCode.AI_PII_GUARDRAIL_UNAVAILABLE, "PII guardrail temporarily unavailable");
    }

    public AiPiiGuardrailUnavailableException(String message, Throwable cause) {
        super(AiErrorCode.AI_PII_GUARDRAIL_UNAVAILABLE,
                "PII guardrail temporarily unavailable", cause);
    }
}
