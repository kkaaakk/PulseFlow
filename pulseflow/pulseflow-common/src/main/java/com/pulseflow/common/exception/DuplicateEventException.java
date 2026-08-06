package com.pulseflow.common.exception;

/**
 * Exception thrown when a duplicate event is detected during ingestion.
 * This indicates the event has already been processed (deduplication).
 */
public class DuplicateEventException extends PulseFlowException {

    private final String eventId;

    public DuplicateEventException(String eventId) {
        super("DUPLICATE_EVENT", "Duplicate event: " + eventId);
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }
}
