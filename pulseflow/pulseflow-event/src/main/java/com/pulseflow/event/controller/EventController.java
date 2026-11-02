package com.pulseflow.event.controller;

import com.pulseflow.common.dto.EventRequest;
import com.pulseflow.common.dto.EventResponse;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.event.service.EventService;
import com.pulseflow.event.service.TrustedEventSource;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /**
     * 先执行请求字段校验，再交由事件服务修正时钟偏差并等待 Kafka 确认，返回事件接收结果。
     */
    @PostMapping
    public ApiResponse<EventResponse> acceptEvent(@Valid @RequestBody EventRequest request,
            @RequestHeader(value = TrustedEventSource.HEADER, required = false) String sourceToken) {
        log.info("Received event: eventId={}, userId={}, eventType={}",
                request.getEventId(), request.getUserId(), request.getEventType());

        EventResponse response = eventService.acceptEvent(request, sourceToken);

        log.info("Event processed: eventId={}, accepted={}", response.getEventId(), response.isAccepted());
        return ApiResponse.success(response);
    }
}
