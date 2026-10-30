package com.pulseflow.common.exception;

/**
 * Base runtime exception for the PulseFlow application.
 * All application-specific exceptions should extend this class.
 */
public class PulseFlowException extends RuntimeException {

    private final String errorCode;

    /**
     * 保存稳定业务错误码与可读消息；带 cause 的重载同时保留原始异常链。
     */
    public PulseFlowException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 保存稳定业务错误码与可读消息；带 cause 的重载同时保留原始异常链。
     */
    public PulseFlowException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 返回稳定的业务错误码，供日志和异常映射使用。
     */
    public String getErrorCode() {
        return errorCode;
    }
}
