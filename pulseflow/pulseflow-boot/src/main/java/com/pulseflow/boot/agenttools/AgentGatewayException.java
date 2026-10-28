package com.pulseflow.boot.agenttools;

/**
 * Agent 网关对外错误，只保存固定错误码和 HTTP 状态。
 * Public fixed error code only, without upstream body or exception text. */
public class AgentGatewayException extends RuntimeException {
    final int status;
    final String code;
    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    AgentGatewayException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
