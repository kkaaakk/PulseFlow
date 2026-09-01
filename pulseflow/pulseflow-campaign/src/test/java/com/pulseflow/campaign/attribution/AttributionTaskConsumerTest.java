package com.pulseflow.campaign.attribution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AttributionTaskConsumerTest {

    private AttributionService attributionService;
    private AttributionTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        attributionService = mock(AttributionService.class);
        consumer = new AttributionTaskConsumer(attributionService);
    }

    @Test
    @DisplayName("successful attribution completes the processing claim")
    void successfulAttributionCompletesClaim() {
        when(attributionService.claimExpiredTasks()).thenReturn(Set.of("target-evt-1"));

        consumer.processAttributionTasks();

        verify(attributionService).claimExpiredTasks();
        verify(attributionService).executeAttribution("target-evt-1");
        verify(attributionService).completeClaimedTask("target-evt-1");
        verify(attributionService, never()).requeueClaimedTask("target-evt-1");
    }

    @Test
    @DisplayName("attribution exception requeues and never completes the processing claim")
    void attributionExceptionRequeuesInsteadOfCompleting() {
        when(attributionService.claimExpiredTasks()).thenReturn(Set.of("target-evt-1"));
        doThrow(new RuntimeException("temporary attribution failure"))
                .when(attributionService).executeAttribution("target-evt-1");

        consumer.processAttributionTasks();

        verify(attributionService).claimExpiredTasks();
        verify(attributionService).executeAttribution("target-evt-1");
        verify(attributionService).requeueClaimedTask("target-evt-1");
        verify(attributionService, never()).completeClaimedTask("target-evt-1");
    }

    @Test
    @DisplayName("a later poll can retry a failed task and complete it after success")
    void laterPollRetriesThenCompletes() {
        when(attributionService.claimExpiredTasks()).thenReturn(Set.of("target-evt-1"));
        doThrow(new RuntimeException("first attempt"))
                .doNothing()
                .when(attributionService).executeAttribution("target-evt-1");

        consumer.processAttributionTasks();
        consumer.processAttributionTasks();

        verify(attributionService, org.mockito.Mockito.times(2))
                .executeAttribution("target-evt-1");
        verify(attributionService).requeueClaimedTask("target-evt-1");
        verify(attributionService).completeClaimedTask("target-evt-1");
    }

    @Test
    @DisplayName("cleanup failure keeps the task discoverable through requeue")
    void cleanupFailureRequeuesClaimedTask() {
        when(attributionService.claimExpiredTasks()).thenReturn(Set.of("target-evt-1"));
        doThrow(new RuntimeException("temporary redis cleanup failure"))
                .when(attributionService).completeClaimedTask("target-evt-1");
        doNothing().when(attributionService).requeueClaimedTask("target-evt-1");

        consumer.processAttributionTasks();

        verify(attributionService).claimExpiredTasks();
        verify(attributionService).executeAttribution("target-evt-1");
        verify(attributionService).completeClaimedTask("target-evt-1");
        verify(attributionService).requeueClaimedTask("target-evt-1");
        verifyNoMoreInteractions(attributionService);
    }
}
