package com.pulseflow.event.controller;

import com.pulseflow.common.dto.EventRequest;
import com.pulseflow.common.dto.EventResponse;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.event.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 行为事件 HTTP 接入入口，执行请求校验并返回接收结果。 */
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /** 校验接入请求并委托事件服务发布，返回统一事件接收结果。 */
    @PostMapping
    public ApiResponse<EventResponse> acceptEvent(@Valid @RequestBody EventRequest request) {
        log.info("Received event: eventId={}, userId={}, eventType={}",
                request.getEventId(), request.getUserId(), request.getEventType());

        EventResponse response = eventService.acceptEvent(request);

        log.info("Event processed: eventId={}, accepted={}", response.getEventId(), response.isAccepted());
        return ApiResponse.success(response);
    }
}
