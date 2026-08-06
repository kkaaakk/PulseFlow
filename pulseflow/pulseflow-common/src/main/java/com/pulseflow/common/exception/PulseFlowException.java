package com.pulseflow.common.exception;

/**
 * Base runtime exception for the PulseFlow application.
 * All application-specific exceptions should extend this class.
 */
public class PulseFlowException extends RuntimeException {

    private final String errorCode;

    public PulseFlowException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PulseFlowException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
