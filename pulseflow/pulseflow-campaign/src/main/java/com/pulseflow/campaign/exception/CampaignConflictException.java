package com.pulseflow.campaign.exception;

/**
 * 活动资源状态与操作冲突时使用的业务异常。
 *
 * Thrown when a Campaign resource is in a state that conflicts with the requested
 * operation (e.g. confirming a non-VALIDATED draft, regenerating a review
 * that is currently PROCESSING). Maps to HTTP 409.
 */
public class CampaignConflictException extends RuntimeException {
    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    public CampaignConflictException(String message) {
        super(message);
    }
}
