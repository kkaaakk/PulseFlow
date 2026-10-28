package com.pulseflow.common.dto;

import lombok.Builder;
import lombok.Data;

/** 事件接入结果，说明事件是否已被接受。 */
@Data
@Builder
public class EventResponse {

    private boolean accepted;
    private String eventId;
    private String message;
}
