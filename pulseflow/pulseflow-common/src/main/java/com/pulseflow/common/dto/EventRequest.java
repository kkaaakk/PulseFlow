package com.pulseflow.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {

    @NotBlank
    private String eventId;

    @NotNull
    private Long userId;

    @NotBlank
    private String eventType;

    private Long targetId;

    @NotNull
    private LocalDateTime eventTime;

    private Map<String, Object> properties;
}
