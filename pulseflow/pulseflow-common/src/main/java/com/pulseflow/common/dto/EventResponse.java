package com.pulseflow.common.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EventResponse {

    private boolean accepted;
    private String eventId;
    private String message;
}
