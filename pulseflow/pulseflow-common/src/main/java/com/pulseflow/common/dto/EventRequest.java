package com.pulseflow.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
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
    @Size(max = 64)
    private String eventId;

    @NotNull
    @Positive
    private Long userId;

    @NotBlank
    @Size(max = 32)
    private String eventType;

    private Long targetId;

    @NotNull
    private LocalDateTime eventTime;

    private Map<String, Object> properties;
}
