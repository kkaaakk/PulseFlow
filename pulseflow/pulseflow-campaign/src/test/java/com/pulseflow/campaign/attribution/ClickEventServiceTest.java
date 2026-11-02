package com.pulseflow.campaign.attribution;

import com.pulseflow.entity.*;
import com.pulseflow.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClickEventServiceTest {
    final ClickEventMapper clicks = mock(ClickEventMapper.class);
    final UserEventMapper events = mock(UserEventMapper.class);
    final DeliveryTaskMapper tasks = mock(DeliveryTaskMapper.class);
    final DeliveryRecordMapper deliveries = mock(DeliveryRecordMapper.class);
    final DataCompensationTaskMapper compensation = mock(DataCompensationTaskMapper.class);
    final ClickEventService service = new ClickEventService(clicks, events, tasks, deliveries, compensation);
    final LocalDateTime time = LocalDateTime.of(2026, 11, 2, 12, 0);
    DeliveryTask task;
    DeliveryRecord delivery;

    @BeforeEach void setUp() {
        task = DeliveryTask.builder().id(5L).userId(10L).campaignId(7L).channel("IN_APP").status("SENT").build();
        delivery = DeliveryRecord.builder().taskId(5L).userId(10L).campaignId(7L).channel("IN_APP")
                .status("SENT").sentAt(time.minusMinutes(1)).build();
        when(tasks.selectById(5L)).thenReturn(task);
        when(deliveries.selectByTaskId(5L)).thenReturn(delivery);
    }

    UserEvent event(String properties) {
        return UserEvent.builder().eventId("click-1").userId(10L).eventType("CLICK")
                .effectiveEventTime(time).marketingTrusted(1).properties(properties).build();
    }

    @Test void ordinaryClicksAndOtherEventsNeverBecomeMarketingFacts() {
        assertThat(service.recordClick(event("{}"))).isEqualTo(ClickEventService.Result.ORDINARY);
        UserEvent other = event("{\"taskId\":5}");
        other.setEventType("CONTENT_VIEW");
        assertThat(service.recordClick(other)).isEqualTo(ClickEventService.Result.ORDINARY);
        verifyNoInteractions(clicks, compensation, events);
    }

    @Test void validClickUsesCanonicalIdAndEffectiveTime() {
        UserEvent e = event("{\"taskId\":5,\"clickSource\":\"IN_APP\"}");
        assertThat(service.recordClick(e)).isEqualTo(ClickEventService.Result.VALID);
        var fact = org.mockito.ArgumentCaptor.forClass(ClickEvent.class);
        verify(clicks).insertCanonical(fact.capture());
        assertThat(fact.getValue().getSourceEventId()).isEqualTo("click-1");
        assertThat(fact.getValue().getClickTime()).isEqualTo(time);
        assertThat(e.getMarketingClickStatus()).isEqualTo("VALID");
        verifyNoInteractions(compensation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "1.5", "5.0", "true", "\"5\"", "{}", "[]", "9223372036854775808"})
    void invalidTaskIdsAreFinalRejections(String value) {
        UserEvent e = event("{\"taskId\":" + value + "}");
        assertThat(service.recordClick(e)).isEqualTo(ClickEventService.Result.REJECTED);
        assertThat(e.getMarketingClickReason()).isEqualTo("INVALID_TASK_ID");
        verifyNoInteractions(clicks, compensation);
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "null", "broken", "42"})
    void invalidPropertiesAreSafelyRejected(String properties) {
        assertThat(service.recordClick(event(properties))).isEqualTo(ClickEventService.Result.REJECTED);
        verifyNoInteractions(clicks, compensation);
    }

    @Test void publicClientCannotForgeProvenanceInsideProperties() {
        UserEvent e = event("{\"taskId\":5,\"marketingTrusted\":true,\"clickSource\":\"IN_APP\"}");
        e.setMarketingTrusted(0);
        assertThat(service.recordClick(e)).isEqualTo(ClickEventService.Result.REJECTED);
        assertThat(e.getMarketingClickReason()).isEqualTo("UNTRUSTED_SOURCE");
        verifyNoInteractions(clicks, compensation);
    }

    @Test void nonexistentTaskIsNotMarketing() {
        UserEvent e = event("{\"taskId\":99}");
        assertThat(service.recordClick(e)).isEqualTo(ClickEventService.Result.REJECTED);
        assertThat(e.getMarketingClickReason()).isEqualTo("TASK_NOT_FOUND");
    }

    @Test void otherUsersTaskIsRejected() {
        task.setUserId(11L);
        UserEvent e = event("{\"taskId\":5}");
        assertThat(service.recordClick(e)).isEqualTo(ClickEventService.Result.REJECTED);
        assertThat(e.getMarketingClickReason()).isEqualTo("TASK_USER_MISMATCH");
        verifyNoInteractions(clicks, compensation);
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "CANCELLED"})
    void terminalUnsentTasksAreRejected(String status) {
        task.setStatus(status);
        assertThat(service.recordClick(event("{\"taskId\":5}"))).isEqualTo(ClickEventService.Result.REJECTED);
        verifyNoInteractions(clicks, compensation);
    }

    @Test void mismatchedReceiptCannotValidateClick() {
        delivery.setCampaignId(99L);
        assertThat(service.recordClick(event("{\"taskId\":5}"))).isEqualTo(ClickEventService.Result.REJECTED);
        verifyNoInteractions(clicks, compensation);
    }

    @Test void clickBeforeSendIsRejectedAndEqualityIsAllowed() {
        delivery.setSentAt(time.plusSeconds(1));
        assertThat(service.recordClick(event("{\"taskId\":5}"))).isEqualTo(ClickEventService.Result.REJECTED);
        delivery.setSentAt(time);
        assertThat(service.recordClick(event("{\"taskId\":5}"))).isEqualTo(ClickEventService.Result.VALID);
    }

    @Test void invisibleReceiptPersistsBoundedRetryWithoutMarketingFact() {
        when(deliveries.selectByTaskId(5L)).thenReturn(null);
        UserEvent e = event("{\"taskId\":5}");
        assertThat(service.recordClick(e)).isEqualTo(ClickEventService.Result.PENDING);
        verify(compensation).ensureMarketingClickPending("click-1");
        verifyNoInteractions(clicks);
    }

    @Test void infrastructureFailureEscapesAndIsNeverBusinessRejection() {
        when(deliveries.selectByTaskId(5L)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.recordClick(event("{\"taskId\":5}")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(clicks, events, compensation);
    }

    @Test void retryUsesLockedCanonicalEvent() {
        UserEvent e = event("{\"taskId\":5}");
        when(events.selectByEventIdForUpdate("click-1")).thenReturn(e);
        assertThat(service.retry("click-1")).isEqualTo(ClickEventService.Result.VALID);
        verify(events).selectByEventIdForUpdate("click-1");
    }
}
