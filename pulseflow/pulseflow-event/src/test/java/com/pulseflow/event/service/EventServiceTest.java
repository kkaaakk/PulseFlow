package com.pulseflow.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseflow.common.dto.EventRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    @DisplayName("HTTP JSON targetId null remains null in EventRequest")
    void deserializesNullTargetId() throws Exception {
        String json = """
                {
                  "eventId": "evt-http-null-target",
                  "userId": 990000001,
                  "eventType": "LOGIN",
                  "targetId": null,
                  "eventTime": "2026-08-31T15:30:00",
                  "properties": {"probe": "target-id-null"}
                }
                """;

        EventRequest request = objectMapper.readValue(json, EventRequest.class);

        assertThat(request.getTargetId()).isNull();
    }

    @Test
    @DisplayName("EventService Kafka payload preserves targetId null")
    void kafkaPayloadContainsJsonNullTargetId() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> acknowledged =
                CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acknowledged);

        EventService service = new EventService(kafkaTemplate);
        EventRequest request = EventRequest.builder()
                .eventId("evt-kafka-null-target")
                .userId(990000001L)
                .eventType("LOGIN")
                .targetId(null)
                .eventTime(LocalDateTime.now().withNano(0))
                .build();

        service.acceptEvent(request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.has("targetId")).isTrue();
        assertThat(payload.get("targetId").isNull()).isTrue();
    }

    @Test
    @DisplayName("EventService Kafka payload preserves targetId zero")
    void kafkaPayloadContainsNumericZeroTargetId() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> acknowledged =
                CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acknowledged);

        EventService service = new EventService(kafkaTemplate);
        EventRequest request = EventRequest.builder()
                .eventId("evt-kafka-zero-target")
                .userId(990000001L)
                .eventType("LOGIN")
                .targetId(0L)
                .eventTime(LocalDateTime.now().withNano(0))
                .build();

        service.acceptEvent(request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.get("targetId").isNumber()).isTrue();
        assertThat(payload.get("targetId").longValue()).isZero();
    }
}
