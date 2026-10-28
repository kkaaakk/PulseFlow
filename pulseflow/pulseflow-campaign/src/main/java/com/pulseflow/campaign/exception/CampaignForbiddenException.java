package com.pulseflow.campaign.exception;

/**
 * 操作员不拥有目标活动或草稿时使用的业务异常。
 *
 * Thrown when the current operator does not own the target Campaign resource
 * (draft or campaign). Maps to HTTP 403.
 */
public class CampaignForbiddenException extends RuntimeException {
    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    public CampaignForbiddenException(String message) {
        super(message);
    }
}
