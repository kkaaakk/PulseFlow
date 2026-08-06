package com.pulseflow.ai.support;

import com.pulseflow.common.exception.PulseFlowException;

/**
 * Thrown when AI output cannot be parsed or fails schema/guardrail validation.
 * Carries one of {@code AI_INVALID_JSON}, {@code AI_OUTPUT_SCHEMA_INVALID},
 * {@code AI_UNKNOWN_FIELD}, {@code AI_INVALID_OPERATOR}, {@code AI_INVALID_VALUE},
 * {@code AI_MISSING_REQUIRED_FACT}, {@code AI_CONTENT_FACT_CONFLICT},
 * {@code AI_EVIDENCE_INVALID}.
 */
public class AiOutputInvalidException extends PulseFlowException {
    public AiOutputInvalidException(String errorCode, String message) {
        super(errorCode, message);
    }

    public AiOutputInvalidException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
