package com.pulseflow.common.util;

/**
 * Utility class for generating deduplication keys for different trigger types.
 * <p>
 * Key formats:
 * <ul>
 *   <li>EVENT trigger: {campaignId}:{userId}:{eventId}</li>
 *   <li>DELAYED trigger: {campaignId}:{userId}:{cartItemId}:{addCartEventId}</li>
 *   <li>SCHEDULED trigger: {campaignExecutionId}:{userId}</li>
 * </ul>
 */
public final class DedupKeyUtil {

    private DedupKeyUtil() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Generates a dedup key for EVENT-triggered campaigns.
     */
    public static String forEvent(Long campaignId, Long userId, String eventId) {
        return campaignId + ":" + userId + ":" + eventId;
    }

    /**
     * Generates a dedup key for DELAYED-triggered campaigns.
     */
    public static String forDelayed(Long campaignId, Long userId, String cartItemId, String addCartEventId) {
        return campaignId + ":" + userId + ":" + cartItemId + ":" + addCartEventId;
    }

    /**
     * Generates a dedup key for SCHEDULED-triggered campaigns.
     */
    public static String forScheduled(Long campaignExecutionId, Long userId) {
        return campaignExecutionId + ":" + userId;
    }
}
