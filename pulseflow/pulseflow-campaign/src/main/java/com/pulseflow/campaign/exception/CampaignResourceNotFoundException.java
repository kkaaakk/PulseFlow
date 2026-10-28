package com.pulseflow.campaign.exception;

/**
 * 活动或草稿不存在时使用的业务异常。
 *
 * Thrown when a Campaign resource is not found.
 * Maps to HTTP 404.
 */
public class CampaignResourceNotFoundException extends RuntimeException {
    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    public CampaignResourceNotFoundException(String message) {
        super(message);
    }
}
