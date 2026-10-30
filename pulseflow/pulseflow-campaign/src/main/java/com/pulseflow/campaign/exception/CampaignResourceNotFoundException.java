package com.pulseflow.campaign.exception;

/**
 * Thrown when a Campaign resource is not found.
 * Maps to HTTP 404.
 */
public class CampaignResourceNotFoundException extends RuntimeException {
    /**
     * 使用给定说明构造资源不存在异常，供 Web 层映射为 404。
     */
    public CampaignResourceNotFoundException(String message) {
        super(message);
    }
}
