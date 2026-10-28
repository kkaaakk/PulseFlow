package com.pulseflow.campaign.attribution;

/**
 * 已领取任务尚未在 MySQL 可见时的可重试异常。
 *
 * Indicates that a claimed attribution task is not visible in MySQL yet (or
 * has disappeared unexpectedly). The consumer must requeue the Redis claim;
 * treating this condition as a successful execution would lose the task.
 */
public class AttributionTaskNotFoundException extends RuntimeException {

    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    public AttributionTaskNotFoundException(String targetEventId) {
        super("Attribution task was not found for targetEventId=" + targetEventId);
    }
}
