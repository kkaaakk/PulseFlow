package com.pulseflow.event.service;

import com.pulseflow.entity.UserEvent;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.mapper.UserMetricHourlyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link EventPersistenceService} — Phase 1 of the event
 * consumption pipeline. Verifies the idempotent duplicate-event handling
 * that protects against Kafka replays.
 *
 * <p>When a {@link DuplicateKeyException} is thrown on insert (meaning the
 * event was already persisted on a previous attempt), the service must NOT
 * fail. Instead it loads the canonical record from MySQL and returns
 * {@code ok=true} so downstream phases reuse the DB-sourced values.</p>
 */
class EventPersistenceServiceTest {

    private UserEventMapper userEventMapper;
    private UserMetricHourlyMapper userMetricHourlyMapper;
    private EventPersistenceService service;

    @BeforeEach
    void setUp() {
        userEventMapper = mock(UserEventMapper.class);
        userMetricHourlyMapper = mock(UserMetricHourlyMapper.class);
        service = new EventPersistenceService(userEventMapper, userMetricHourlyMapper);
    }

    @Test
    @DisplayName("duplicate eventId → load canonical record from DB → ok=true (idempotent)")
    void duplicateEventIdIsIdempotent() {
        // A valid raw event map (enough for buildUserEvent to succeed before insert throws)
        Map<String, Object> rawEventMap = new HashMap<>();
        rawEventMap.put("eventId", "evt-dup-1");
        rawEventMap.put("userId", 1001L);
        rawEventMap.put("eventType", "PAGE_VIEW");
        rawEventMap.put("targetId", 0L);
        rawEventMap.put("eventTime", "2026-08-06 10:00:00");
        rawEventMap.put("receivedAt", "2026-08-06 10:00:01");
        rawEventMap.put("effectiveEventTime", "2026-08-06 10:00:00");
        rawEventMap.put("clockSkew", false);
        rawEventMap.put("properties", Map.of("page", "home"));

        // Insert fails — event already exists (e.g. Kafka replay after a crash)
        doThrow(new DuplicateKeyException("Duplicate eventId"))
                .when(userEventMapper).insert(any());

        // The canonical record already in MySQL
        UserEvent existing = UserEvent.builder()
                .id(1L)
                .eventId("evt-dup-1")
                .userId(1001L)
                .eventType("PAGE_VIEW")
                .targetId(0L)
                .eventTime(LocalDateTime.of(2026, 8, 6, 10, 0, 0))
                .receivedAt(LocalDateTime.of(2026, 8, 6, 10, 0, 1))
                .effectiveEventTime(LocalDateTime.of(2026, 8, 6, 10, 0, 0))
                .clockSkew(0)
                .properties("{\"page\":\"home\"}")
                .build();
        when(userEventMapper.selectOne(any())).thenReturn(existing);

        // persist() catches DuplicateKeyException internally and loads the canonical row.
        // No exception should propagate.
        EventPersistenceService.PersistResult result = service.persist(rawEventMap);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getEvent()).isNotNull();
        assertThat(result.getEvent().getEventId()).isEqualTo("evt-dup-1");
        assertThat(result.getContext()).isNotNull();
        assertThat(result.getContext().get("eventId")).isEqualTo("evt-dup-1");

        // selectOne was called to load the canonical record
        verify(userEventMapper).selectOne(any());
        // upsertAccumulate was NOT called (it's after insert in the try block, which threw)
        verify(userMetricHourlyMapper, never())
                .upsertAccumulate(any(), any(), any(), anyInt(), anyLong(), any());
    }
}
