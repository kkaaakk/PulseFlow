package com.pulseflow.campaign.attribution;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pulseflow.entity.AttributionRecord;
import com.pulseflow.entity.AttributionTask;
import com.pulseflow.entity.ClickEvent;
import com.pulseflow.entity.DeliveryRecord;
import com.pulseflow.mapper.AttributionRecordMapper;
import com.pulseflow.mapper.AttributionTaskMapper;
import com.pulseflow.mapper.ClickEventMapper;
import com.pulseflow.mapper.DeliveryRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AttributionService} — the last-touch click attribution
 * logic. All mappers and RedissonClient are mocked; no Docker required.
 *
 * <p>Covers the 3 core attribution paths: successful last-touch match, window
 * expiry with no clicks, and idempotent duplicate-insert handling.</p>
 */
class AttributionServiceTest {

    /**
     * MyBatis-Plus {@code LambdaUpdateWrapper.set()} resolves the entity's column
     * cache eagerly (unlike {@code LambdaQueryWrapper.eq()} which is lazy). Without
     * a Spring context the lambda cache is never populated, so we must initialize
     * the {@link TableInfoHelper} for {@link AttributionTask} manually.
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AttributionTask.class);
    }

    private AttributionTaskMapper attributionTaskMapper;
    private AttributionRecordMapper attributionRecordMapper;
    private ClickEventMapper clickEventMapper;
    private DeliveryRecordMapper deliveryRecordMapper;
    private RedissonClient redissonClient;
    private AttributionService attributionService;

    @BeforeEach
    void setUp() {
        attributionTaskMapper = mock(AttributionTaskMapper.class);
        attributionRecordMapper = mock(AttributionRecordMapper.class);
        clickEventMapper = mock(ClickEventMapper.class);
        deliveryRecordMapper = mock(DeliveryRecordMapper.class);
        redissonClient = mock(RedissonClient.class);
        attributionService = new AttributionService(
                attributionTaskMapper, attributionRecordMapper,
                clickEventMapper, deliveryRecordMapper, redissonClient);
    }

    // ---------- helpers ----------

    private AttributionTask pendingTask() {
        return AttributionTask.builder()
                .id(1L)
                .targetEventId("target-evt-1")
                .userId(1001L)
                .targetEventType("ORDER_PAID")
                .targetEventTime(LocalDateTime.of(2026, 8, 6, 12, 0, 0))
                .status("PENDING")
                .graceUntil(LocalDateTime.of(2026, 8, 6, 12, 5, 0))
                .build();
    }

    private ClickEvent clickAt(LocalDateTime time, Long taskId) {
        return ClickEvent.builder()
                .id(10L)
                .userId(1001L)
                .taskId(taskId)
                .clickTime(time)
                .clickSource("IN_APP")
                .build();
    }

    private DeliveryRecord deliverySentAt(LocalDateTime sentAt, Long taskId) {
        return DeliveryRecord.builder()
                .id(20L)
                .taskId(taskId)
                .userId(1001L)
                .campaignId(300L)
                .channel("IN_APP")
                .status("SENT")
                .sentAt(sentAt)
                .build();
    }

    // ---------- tests ----------

    @Test
    @DisplayName("click after delivery within 24h window → MATCHED")
    void lastTouchAttributionMatchesClickAfterDelivery() {
        AttributionTask task = pendingTask();
        when(attributionTaskMapper.selectOne(any())).thenReturn(task);

        // Click at 10:00 — within 24h before target (12:00) and after delivery sentAt (09:00)
        ClickEvent click = clickAt(LocalDateTime.of(2026, 8, 6, 10, 0, 0), 500L);
        when(clickEventMapper.selectList(any())).thenReturn(List.of(click));

        DeliveryRecord delivery = deliverySentAt(LocalDateTime.of(2026, 8, 6, 9, 0, 0), 500L);
        when(deliveryRecordMapper.selectOne(any())).thenReturn(delivery);
        when(attributionRecordMapper.insert(any())).thenReturn(1);

        attributionService.executeAttribution("target-evt-1");

        // Attribution record inserted
        verify(attributionRecordMapper).insert(any(AttributionRecord.class));
        // Task updated to MATCHED with the click's taskId
        verify(attributionTaskMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("no clicks in 24h window → EXPIRED")
    void noClickInWindowExpiresTask() {
        AttributionTask task = pendingTask();
        when(attributionTaskMapper.selectOne(any())).thenReturn(task);
        when(clickEventMapper.selectList(any())).thenReturn(List.of());

        attributionService.executeAttribution("target-evt-1");

        // No attribution record created
        verify(attributionRecordMapper, never()).insert(any());
        // Task updated to EXPIRED
        verify(attributionTaskMapper).update(eq(null), any());
    }

    @Test
    @DisplayName("duplicate attribution insert is idempotent — no re-throw, no MATCHED update")
    void duplicateAttributionIsIdempotent() {
        AttributionTask task = pendingTask();
        when(attributionTaskMapper.selectOne(any())).thenReturn(task);

        ClickEvent click = clickAt(LocalDateTime.of(2026, 8, 6, 10, 0, 0), 500L);
        when(clickEventMapper.selectList(any())).thenReturn(List.of(click));

        DeliveryRecord delivery = deliverySentAt(LocalDateTime.of(2026, 8, 6, 9, 0, 0), 500L);
        when(deliveryRecordMapper.selectOne(any())).thenReturn(delivery);

        // Insert fails — attribution already recorded by a previous run
        doThrow(new DuplicateKeyException("Duplicate target_event_id"))
                .when(attributionRecordMapper).insert(any());

        // Must not propagate — duplicate is a business-level idempotent skip
        assertThatCode(() -> attributionService.executeAttribution("target-evt-1"))
                .doesNotThrowAnyException();

        // Insert was attempted
        verify(attributionRecordMapper).insert(any(AttributionRecord.class));
        // Task was NOT updated to MATCHED (insert failed before the update)
        // and NOT updated to EXPIRED (the return inside the if-block short-circuits)
        verify(attributionTaskMapper, never()).update(any(), any());
    }
}
