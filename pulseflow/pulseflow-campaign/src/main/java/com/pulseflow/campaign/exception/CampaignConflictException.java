package com.pulseflow.campaign.exception;

/**
 * Thrown when a Campaign resource is in a state that conflicts with the requested
 * operation (e.g. confirming a non-VALIDATED draft, regenerating a review
 * that is currently PROCESSING). Maps to HTTP 409.
 */
public class CampaignConflictException extends RuntimeException {
    /**
     * 使用给定说明构造 Campaign 状态冲突异常，供 Web 层映射为 409。
     */
    public CampaignConflictException(String message) {
        super(message);
    }
}
