package com.pulseflow.common.exception;

/**
 * 应用业务异常基类，携带稳定错误码与说明。
 *
 * Base runtime exception for the PulseFlow application.
 * All application-specific exceptions should extend this class.
 */
public class PulseFlowException extends RuntimeException {

    private final String errorCode;

    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    public PulseFlowException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    public PulseFlowException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 返回应用错误码，供统一异常响应使用。 */
    public String getErrorCode() {
        return errorCode;
    }
}
