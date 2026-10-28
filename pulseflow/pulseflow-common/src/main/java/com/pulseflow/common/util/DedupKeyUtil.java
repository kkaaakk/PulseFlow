package com.pulseflow.common.util;

/**
 * 按即时、延迟和定时触发维度生成触达幂等键。
 *
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

    /** 隐藏工具类构造入口，避免创建没有状态的工具类实例。 */
    private DedupKeyUtil() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * 按活动、用户和事件 ID 生成即时触达幂等键。
     *
     * Generates a dedup key for EVENT-triggered campaigns.
     */
    public static String forEvent(Long campaignId, Long userId, String eventId) {
        return campaignId + ":" + userId + ":" + eventId;
    }

    /**
     * 按活动、用户、购物车项和加购事件生成延迟触达幂等键。
     *
     * Generates a dedup key for DELAYED-triggered campaigns.
     */
    public static String forDelayed(Long campaignId, Long userId, String cartItemId, String addCartEventId) {
        return campaignId + ":" + userId + ":" + cartItemId + ":" + addCartEventId;
    }

    /**
     * 按定时执行实例和用户生成幂等键，隔离不同周期的触达。
     *
     * Generates a dedup key for SCHEDULED-triggered campaigns.
     */
    public static String forScheduled(Long campaignExecutionId, Long userId) {
        return campaignExecutionId + ":" + userId;
    }
}
