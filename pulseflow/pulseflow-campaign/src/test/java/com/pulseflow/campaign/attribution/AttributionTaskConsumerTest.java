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

/** 验证 AttributionTaskConsumer 的业务行为和边界条件。 */
class AttributionTaskConsumerTest {

    private AttributionService attributionService;
    private AttributionTaskConsumer consumer;

    /** 初始化当前测试的实体、依赖替身和数据，使各场景之间保持隔离。 */
    @BeforeEach
    void setUp() {
        attributionService = mock(AttributionService.class);
        consumer = new AttributionTaskConsumer(attributionService);
    }

    /** 验证归因成功后清理领取记录。 */
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

    /** 验证执行异常时重新排队且不误标完成。 */
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

    /** 验证定时协调任务调用待处理调度修复流程。 */
    @Test
    @DisplayName("scheduled reconciliation restores orphaned PENDING tasks")
    void scheduledReconciliationRestoresOrphanedTasks() {
        when(attributionService.reconcilePendingTasks()).thenReturn(1);

        consumer.reconcileOrphanedTasks();

        verify(attributionService).reconcilePendingTasks();
    }

    /** 验证首次失败重排后，下一轮轮询可成功完成。 */
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

    /** 验证清理领取标记失败时保留重试机会。 */
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
