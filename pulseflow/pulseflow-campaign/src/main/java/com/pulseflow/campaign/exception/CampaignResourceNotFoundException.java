package com.pulseflow.campaign.exception;

/**
 * Thrown when a Campaign resource is not found.
 * Maps to HTTP 404.
 */
public class CampaignResourceNotFoundException extends RuntimeException {
    public CampaignResourceNotFoundException(String message) {
        super(message);
    }
}
