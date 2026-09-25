package com.pulseflow.campaign.exception;

/**
 * Thrown when a Campaign resource is in a state that conflicts with the requested
 * operation (e.g. confirming a non-VALIDATED draft, regenerating a review
 * that is currently PROCESSING). Maps to HTTP 409.
 */
public class CampaignConflictException extends RuntimeException {
    public CampaignConflictException(String message) {
        super(message);
    }
}
