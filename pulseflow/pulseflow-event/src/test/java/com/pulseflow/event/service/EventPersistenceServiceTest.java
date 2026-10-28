package com.pulseflow.event.service;

import com.pulseflow.entity.UserEvent;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.mapper.UserMetricHourlyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 验证 EventPersistenceService 的业务行为和边界条件。
 *
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

    /** 初始化当前测试的实体、依赖替身和数据，使各场景之间保持隔离。 */
    @BeforeEach
    void setUp() {
        userEventMapper = mock(UserEventMapper.class);
        userMetricHourlyMapper = mock(UserMetricHourlyMapper.class);
        service = new EventPersistenceService(userEventMapper, userMetricHourlyMapper);
    }

    /** 验证重复事件 ID 不重复写入事实和统计。 */
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

    /** 验证分钟精度与小数秒精度 ISO 时间均可持久化。 */
    @Test
    @DisplayName("支持 LocalDateTime 的分钟格式和小数秒格式")
    void acceptsIsoMinuteAndFractionalSecondTimestamps() {
        Map<String, Object> rawEventMap = new HashMap<>();
        rawEventMap.put("eventId", "evt-time-format-1");
        rawEventMap.put("userId", 1002L);
        rawEventMap.put("eventType", "CONTENT_VIEW");
        rawEventMap.put("targetId", 2001L);
        // 秒数为 0 时，LocalDateTime.toString() 会生成这种较短格式。
        rawEventMap.put("eventTime", "2026-08-23T23:00");
        rawEventMap.put("receivedAt", "2026-08-28T15:45:37.450775");
        rawEventMap.put("effectiveEventTime", "2026-08-28T15:45:37.450775");
        rawEventMap.put("clockSkew", true);
        rawEventMap.put("properties", Map.of("duration", 1000));

        EventPersistenceService.PersistResult result = service.persist(rawEventMap);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getEvent().getEventTime())
                .isEqualTo(LocalDateTime.of(2026, 8, 23, 23, 0));
        assertThat(result.getEvent().getReceivedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 15, 45, 37, 450_775_000));
        verify(userEventMapper).insert(any(UserEvent.class));
        verify(userMetricHourlyMapper).upsertAccumulate(
                any(), any(), any(), anyInt(), anyLong(), any());
    }

    /** 验证事件落库保留 null 目标标识。 */
    @Test
    @DisplayName("缺少 targetId 时保留数据库 NULL 语义")
    void preservesNullTargetId() {
        Map<String, Object> rawEventMap = new HashMap<>();
        rawEventMap.put("eventId", "evt-null-target-1");
        rawEventMap.put("userId", 1003L);
        rawEventMap.put("eventType", "LOGIN");
        rawEventMap.put("targetId", null);
        rawEventMap.put("eventTime", "2026-08-23T23:00");
        rawEventMap.put("receivedAt", "2026-08-23T23:00:01");
        rawEventMap.put("effectiveEventTime", "2026-08-23T23:00");
        rawEventMap.put("clockSkew", false);
        rawEventMap.put("properties", Map.of());

        service.persist(rawEventMap);

        ArgumentCaptor<UserEvent> eventCaptor = ArgumentCaptor.forClass(UserEvent.class);
        verify(userEventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTargetId()).isNull();
    }

    /** 验证数值目标标识在事件转换后保持不变。 */
    @ParameterizedTest(name = "targetId={0}")
    @ValueSource(longs = {0L, 123L})
    @DisplayName("非空 targetId 保持原始数值")
    void preservesNumericTargetId(long targetId) {
        Map<String, Object> rawEventMap = new HashMap<>();
        rawEventMap.put("eventId", "evt-numeric-target-" + targetId);
        rawEventMap.put("userId", 1004L);
        rawEventMap.put("eventType", "CONTENT_VIEW");
        rawEventMap.put("targetId", targetId);
        rawEventMap.put("eventTime", "2026-08-23T23:00");
        rawEventMap.put("receivedAt", "2026-08-23T23:00:01");
        rawEventMap.put("effectiveEventTime", "2026-08-23T23:00");
        rawEventMap.put("clockSkew", false);
        rawEventMap.put("properties", Map.of());

        service.persist(rawEventMap);

        ArgumentCaptor<UserEvent> eventCaptor = ArgumentCaptor.forClass(UserEvent.class);
        verify(userEventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTargetId()).isEqualTo(targetId);
    }
}
