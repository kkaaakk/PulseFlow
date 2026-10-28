package com.pulseflow.common.exception;

/**
 * 标识接入事件重复的业务异常，携带原事件 ID。
 *
 * Exception thrown when a duplicate event is detected during ingestion.
 * This indicates the event has already been processed (deduplication).
 */
public class DuplicateEventException extends PulseFlowException {

    private final String eventId;

    /** 保存异常的业务信息，并交由上层异常处理器转换为接口响应。 */
    public DuplicateEventException(String eventId) {
        super("DUPLICATE_EVENT", "Duplicate event: " + eventId);
        this.eventId = eventId;
    }

    /** 返回重复事件的业务 ID，供幂等异常处理和日志定位使用。 */
    public String getEventId() {
        return eventId;
    }
}
