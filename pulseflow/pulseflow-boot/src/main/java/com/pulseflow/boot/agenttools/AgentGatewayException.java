package com.pulseflow.boot.agenttools;

/** Public fixed error code only, without upstream body or exception text. */
public class AgentGatewayException extends RuntimeException {
    final int status;
    final String code;
    AgentGatewayException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
