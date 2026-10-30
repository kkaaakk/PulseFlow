package com.pulseflow.boot.agenttools;

/** Public fixed error code only, without upstream body or exception text. */
public class AgentGatewayException extends RuntimeException {
    final int status;
    final String code;
    /**
     * 保存对外 HTTP 状态码与稳定错误码；网关仅返回错误码，避免透出内部异常信息。
     */
    AgentGatewayException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
