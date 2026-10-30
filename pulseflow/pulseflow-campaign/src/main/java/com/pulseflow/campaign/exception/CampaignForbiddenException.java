package com.pulseflow.campaign.exception;

/**
 * Thrown when the current operator does not own the target Campaign resource
 * (draft or campaign). Maps to HTTP 403.
 */
public class CampaignForbiddenException extends RuntimeException {
    /**
     * 使用给定说明构造 Campaign 权限异常，供 Web 层映射为 403。
     */
    public CampaignForbiddenException(String message) {
        super(message);
    }
}
