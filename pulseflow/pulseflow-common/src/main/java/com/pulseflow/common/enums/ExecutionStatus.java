package com.pulseflow.common.enums;

/** 定时活动执行实例状态，支持领取、完成与失败恢复。 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}
