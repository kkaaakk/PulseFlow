package com.pulseflow.simulator.controller;

import com.pulseflow.common.dto.EventRequest;
import com.pulseflow.common.enums.EventType;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.event.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/api/simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final EventService eventService;

    /**
     * Generate a random user event for testing.
     * GET /api/simulator/generate?userId=1024&eventType=CONTENT_VIEW&targetId=100
     */
    @GetMapping("/generate")
    public ApiResponse<?> generateEvent(
            @RequestParam(defaultValue = "1024") Long userId,
            @RequestParam(defaultValue = "CONTENT_VIEW") String eventType,
            @RequestParam(defaultValue = "0") Long targetId) {

        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        
        Map<String, Object> props = new HashMap<>();
        props.put("category", "AI");
        props.put("price", 29.90);
        props.put("cartItemId", "ci_" + ThreadLocalRandom.current().nextInt(100, 999));

        EventRequest request = EventRequest.builder()
                .eventId(eventId)
                .userId(userId)
                .eventType(eventType)
                .targetId(targetId > 0 ? targetId : ThreadLocalRandom.current().nextLong(1000, 9999))
                .eventTime(LocalDateTime.now())
                .properties(props)
                .build();

        return ApiResponse.success(eventService.acceptEvent(request));
    }

    /**
     * Generate a batch of events for a simulated user journey.
     * GET /api/simulator/user-journey?userId=1024
     * Generates: LOGIN → CONTENT_VIEW → ADD_CART → ORDER_PAID
     */
    @GetMapping("/user-journey")
    public ApiResponse<?> simulateUserJourney(@RequestParam(defaultValue = "1024") Long userId) {
        long productId = ThreadLocalRandom.current().nextLong(1000, 9999);
        String cartItemId = "ci_" + ThreadLocalRandom.current().nextInt(100, 999);

        Map<String, Object> baseProps = new HashMap<>();
        baseProps.put("category", "AI");
        baseProps.put("price", 29.90);
        baseProps.put("cartItemId", cartItemId);

        log.info("Simulating user journey for userId={}, productId={}", userId, productId);

        // LOGIN
        sendEvent(userId, "LOGIN", productId, baseProps);
        // CONTENT_VIEW
        sendEvent(userId, "CONTENT_VIEW", productId, baseProps);
        // ADD_CART
        sendEvent(userId, "ADD_CART", productId, baseProps);
        // ORDER_PAID
        Map<String, Object> orderProps = new HashMap<>(baseProps);
        orderProps.put("orderId", "ord_" + System.currentTimeMillis());
        sendEvent(userId, "ORDER_PAID", productId, orderProps);

        return ApiResponse.success("User journey simulated: LOGIN → CONTENT_VIEW → ADD_CART → ORDER_PAID");
    }

    /**
     * Generate bulk random events for load testing.
     * GET /api/simulator/bulk?count=100
     */
    @GetMapping("/bulk")
    public ApiResponse<?> generateBulk(@RequestParam(defaultValue = "100") int count) {
        EventType[] types = EventType.values();
        int success = 0;
        int failed = 0;

        for (int i = 0; i < count; i++) {
            try {
                EventType type = types[ThreadLocalRandom.current().nextInt(types.length)];
                generateEvent(ThreadLocalRandom.current().nextLong(1, 10000),
                        type.name(),
                        ThreadLocalRandom.current().nextLong(1000, 9999));
                success++;
            } catch (Exception e) {
                failed++;
            }
        }

        return ApiResponse.success(Map.of("total", count, "success", success, "failed", failed));
    }

    private void sendEvent(Long userId, String eventType, Long targetId, Map<String, Object> props) {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        EventRequest request = EventRequest.builder()
                .eventId(eventId)
                .userId(userId)
                .eventType(eventType)
                .targetId(targetId)
                .eventTime(LocalDateTime.now())
                .properties(props)
                .build();
        eventService.acceptEvent(request);
    }
}
