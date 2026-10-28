package com.pulseflow.common.enums;

/** 触达发送生命周期状态，包含等待重试和终态。 */
public enum TaskStatus {
    PENDING,
    PROCESSING,
    SENT,
    WAIT_RETRY,
    CANCELLED,
    FAILED
}
