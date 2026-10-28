package com.pulseflow.simulator.controller;

import com.pulseflow.common.dto.EventRequest;
import com.pulseflow.common.enums.EventType;
import com.pulseflow.common.model.ApiResponse;
import com.pulseflow.event.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 通过真实事件服务生成单事件、用户旅程和批量演示数据。 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "pulseflow.simulator.enabled", havingValue = "true")
@RequestMapping("/api/simulator")
@RequiredArgsConstructor
public class SimulatorController {

    private final EventService eventService;

    /**
     * 为指定用户生成单个模拟事件，方便验证接入和下游处理链路。
     *
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
     * 按登录、浏览、加购和支付顺序生成演示用户旅程事件。
     *
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
     * 批量生成随机事件，用于演示或本地负载验证。
     *
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

    /** 构建模拟事件请求并交给正式事件服务，复用真实接入链路。 */
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
