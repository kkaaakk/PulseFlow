package com.pulseflow.campaign.attribution;

/**
 * Indicates that a claimed attribution task is not visible in MySQL yet (or
 * has disappeared unexpectedly). The consumer must requeue the Redis claim;
 * treating this condition as a successful execution would lose the task.
 */
public class AttributionTaskNotFoundException extends RuntimeException {

    public AttributionTaskNotFoundException(String targetEventId) {
        super("Attribution task was not found for targetEventId=" + targetEventId);
    }
}
