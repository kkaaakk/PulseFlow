package com.pulseflow.event.service;

import com.pulseflow.common.dto.EventRequest;
import com.pulseflow.common.dto.EventResponse;
import com.pulseflow.common.exception.PulseFlowException;
import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final String TOPIC = "pulseflow.raw.events";
    private static final long MAX_TIME_SKEW_MINUTES = 5;
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 10;

    /**
     * Accepts an incoming event request, validates the event time for clock skew,
     * and sends it to the Kafka topic for downstream processing.
     *
     * @param request the event request containing event details
     * @return EventResponse indicating acceptance status
     * @throws PulseFlowException if Kafka send fails
     */
    public EventResponse acceptEvent(EventRequest request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveEventTime;
        boolean clockSkew = false;

        Duration skew = Duration.between(request.getEventTime(), now).abs();
        if (skew.toMinutes() > MAX_TIME_SKEW_MINUTES) {
            clockSkew = true;
            effectiveEventTime = now;
            log.warn("Event {} clock skew detected: eventTime={}, receivedAt={}, skew={}s",
                    request.getEventId(), request.getEventTime(), now, skew.getSeconds());
        } else {
            effectiveEventTime = request.getEventTime();
        }

        String eventJson = buildEventPayload(request, now, effectiveEventTime, clockSkew);
        String key = String.valueOf(request.getUserId());

        sendToKafka(key, eventJson, request.getEventId());

        log.info("Event accepted: eventId={}, userId={}, type={}, clockSkew={}",
                request.getEventId(), request.getUserId(), request.getEventType(), clockSkew);

        return EventResponse.builder()
                .accepted(true)
                .eventId(request.getEventId())
                .message("Event accepted")
                .build();
    }

    /**
     * Builds the JSON payload to be sent to Kafka.
     */
    private String buildEventPayload(EventRequest request, LocalDateTime receivedAt,
                                     LocalDateTime effectiveEventTime, boolean clockSkew) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", request.getEventId());
        payload.put("userId", request.getUserId());
        payload.put("eventType", request.getEventType());
        payload.put("targetId", request.getTargetId());
        payload.put("eventTime", request.getEventTime().toString());
        payload.put("receivedAt", receivedAt.toString());
        payload.put("effectiveEventTime", effectiveEventTime.toString());
        payload.put("clockSkew", clockSkew);
        if (request.getProperties() != null && !request.getProperties().isEmpty()) {
            payload.put("properties", request.getProperties());
        }

        return JsonUtil.toJson(payload);
    }

    /**
     * Sends the event JSON to Kafka and waits for broker acknowledgment.
     *
     * @throws PulseFlowException if the send fails or times out
     */
    private void sendToKafka(String key, String eventJson, String eventId) {
        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(TOPIC, key, eventJson);
            future.get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Kafka send interrupted: eventId={}", eventId, e);
            throw new PulseFlowException("KAFKA_SEND_INTERRUPTED",
                    "Kafka send was interrupted for event: " + eventId, e);
        } catch (ExecutionException e) {
            log.error("Kafka send failed: eventId={}", eventId, e);
            throw new PulseFlowException("KAFKA_SEND_FAILED",
                    "Failed to send event to Kafka: " + eventId, e);
        } catch (TimeoutException e) {
            log.error("Kafka send timeout: eventId={}", eventId, e);
            throw new PulseFlowException("KAFKA_SEND_TIMEOUT",
                    "Kafka send timed out for event: " + eventId, e);
        }
    }
}
