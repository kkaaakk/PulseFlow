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

    /**
     * 初始化归因消费者及其队列、服务替身。
     */
    @BeforeEach
    void setUp() {
        attributionService = mock(AttributionService.class);
        consumer = new AttributionTaskConsumer(attributionService);
    }

    /**
     * 验证归因成功后完成队列 claim。
     */
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

    /**
     * 验证归因异常重新入队，不错误完成任务。
     */
    @Test
    @DisplayName("a temporarily invisible DB task requeues and never completes the claim")
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

    /**
     * 验证定时对账恢复孤立的待处理归因任务。
     */
    @Test
    @DisplayName("scheduled reconciliation restores orphaned PENDING tasks")
    void scheduledReconciliationRestoresOrphanedTasks() {
        when(attributionService.reconcilePendingTasks()).thenReturn(1);

        consumer.reconcileOrphanedTasks();

        verify(attributionService).reconcilePendingTasks();
    }

    /**
     * 验证后续轮询可重试失败任务并最终完成。
     */
    @Test
    @DisplayName("a later poll can retry a failed task and complete it after success")
    void laterPollRetriesThenCompletes() {
        when(attributionService.claimExpiredTasks()).thenReturn(Set.of("target-evt-1"));
        doThrow(new RuntimeException("first attempt"))
                .doReturn(AttributionService.ExecutionResult.MATCHED)
                .when(attributionService).executeAttribution("target-evt-1");

        consumer.processAttributionTasks();
        consumer.processAttributionTasks();

        verify(attributionService, org.mockito.Mockito.times(2))
                .executeAttribution("target-evt-1");
        verify(attributionService).requeueClaimedTask("target-evt-1");
        verify(attributionService).completeClaimedTask("target-evt-1");
    }

    /**
     * 验证处理后清理失败时 claim 被重新排队。
     */
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
