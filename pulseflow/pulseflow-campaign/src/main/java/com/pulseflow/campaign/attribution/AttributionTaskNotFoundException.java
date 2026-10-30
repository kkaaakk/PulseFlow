package com.pulseflow.campaign.attribution;

/**
 * Indicates that a claimed attribution task is not visible in MySQL yet (or
 * has disappeared unexpectedly). The consumer must requeue the Redis claim;
 * treating this condition as a successful execution would lose the task.
 */
public class AttributionTaskNotFoundException extends RuntimeException {

    /**
     * 记录缺失归因任务对应的目标事件 ID，供调用方区分任务尚未建立的情况。
     */
    public AttributionTaskNotFoundException(String targetEventId) {
        super("Attribution task was not found for targetEventId=" + targetEventId);
    }
}
