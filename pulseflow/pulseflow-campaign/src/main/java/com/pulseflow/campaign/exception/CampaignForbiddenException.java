package com.pulseflow.campaign.exception;

/**
 * Thrown when the current operator does not own the target Campaign resource
 * (draft or campaign). Maps to HTTP 403.
 */
public class CampaignForbiddenException extends RuntimeException {
    public CampaignForbiddenException(String message) {
        super(message);
    }
}
