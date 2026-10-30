package com.pulseflow.common.exception;

/**
 * Exception thrown when a duplicate event is detected during ingestion.
 * This indicates the event has already been processed (deduplication).
 */
public class DuplicateEventException extends PulseFlowException {

    private final String eventId;

    /**
     * 以 DUPLICATE_EVENT 错误码构造异常，并保留触发重复检查的业务事件 ID。
     */
    public DuplicateEventException(String eventId) {
        super("DUPLICATE_EVENT", "Duplicate event: " + eventId);
        this.eventId = eventId;
    }

    /**
     * 返回发生幂等冲突的业务事件 ID。
     */
    public String getEventId() {
        return eventId;
    }
}
