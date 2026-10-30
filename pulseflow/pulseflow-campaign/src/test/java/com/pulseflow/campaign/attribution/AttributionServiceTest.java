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
import org.redisson.api.RScript;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AttributionService} — the last-touch click attribution
 * logic. All mappers and RedissonClient are mocked; no Docker required.
 *
 * <p>Covers successful and expired matching, idempotent duplicate inserts,
 * atomic claim/requeue scripts, and recovery of orphaned PENDING tasks.</p>
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

    /**
     * 装配归因 Mapper、Redis 脚本与服务替身，重置每个用例的数据和交互记录。
     */
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

        // MyBatis returns the affected-row count from status transitions. A
        // successful unit-test fixture represents one updated PENDING row.
        when(attributionTaskMapper.update(any(), any())).thenReturn(1);
    }

    // ---------- helpers ----------

    /**
     * 构造等待指定目标事件归因的 PENDING 任务。
     */
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

    /**
     * 构造指定时间的点击记录，用于比较末次点击与归因窗口。
     */
    private ClickEvent clickAt(LocalDateTime time, Long taskId) {
        return ClickEvent.builder()
                .id(10L)
                .userId(1001L)
                .taskId(taskId)
                .clickTime(time)
                .clickSource("IN_APP")
                .build();
    }

    /**
     * 构造已发送触达记录，供点击时间与发送时间约束测试使用。
     */
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

    /**
     * 验证归因选择触达后窗口内的末次有效点击。
     */
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

    /**
     * 验证窗口内无有效点击时任务转为 EXPIRED。
     */
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

    /**
     * 验证同一目标事件重复归因不会产生重复业务结果。
     */
    @Test
    @DisplayName("duplicate attribution insert is idempotent — no re-throw and task becomes MATCHED")
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
        // The existing unique record is a successful business result; the
        // waiting task must be closed before the Redis claim is completed.
        verify(attributionTaskMapper).update(eq(null), any());
    }

    /**
     * 验证处理失败后通过原子脚本把 claim 重新放回待处理队列。
     */
    @Test
    @DisplayName("failed claim is atomically requeued with a short future retry score")
    void failedClaimIsRequeuedAtomically() {
        RScript script = mock(RScript.class);
        when(redissonClient.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution:processing", "delay:attribution")),
                eq("target-evt-1"), anyLong())).thenReturn(1L);

        long before = System.currentTimeMillis();
        attributionService.requeueClaimedTask("target-evt-1");
        long after = System.currentTimeMillis();

        var retryAt = org.mockito.ArgumentCaptor.forClass(Long.class);
        var scriptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), scriptCaptor.capture(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution:processing", "delay:attribution")),
                eq("target-evt-1"), retryAt.capture());

        assertThat(retryAt.getValue()).isBetween(
                before + AttributionService.ATTRIBUTION_RETRY_DELAY_SECONDS * 1000L,
                after + AttributionService.ATTRIBUTION_RETRY_DELAY_SECONDS * 1000L);
        assertThat(scriptCaptor.getValue()).contains("ZSCORE", "ZREM", "ZADD");
        verify(redissonClient, never()).getScoredSortedSet(anyString());
    }

    /**
     * 验证成功处理后清除 processing 集合中的 claim。
     */
    @Test
    @DisplayName("successful claim completion removes the processing member")
    void completedClaimIsRemovedFromProcessingQueue() {
        RScoredSortedSet<String> processing = mock(RScoredSortedSet.class);
        when(redissonClient.<String>getScoredSortedSet("delay:attribution:processing"))
                .thenReturn(processing);

        attributionService.completeClaimedTask("target-evt-1");

        verify(processing).remove("target-evt-1");
    }

    /**
     * 验证重复目标事件能够恢复数据库已有但 Redis 丢失的 PENDING 任务。
     */
    @Test
    @DisplayName("duplicate target event restores an orphan PENDING task only when both queues are empty")
    void duplicateTargetEventRecoversOrphanedPendingTask() {
        AttributionTask task = pendingTask();
        task.setGraceUntil(LocalDateTime.now().minusMinutes(1));
        doThrow(new DuplicateKeyException("Duplicate target_event_id"))
                .when(attributionTaskMapper).insert(any());
        when(attributionTaskMapper.selectOne(any())).thenReturn(task);

        RScript script = mock(RScript.class);
        when(redissonClient.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                eq("target-evt-1"), anyLong())).thenReturn(1L);

        attributionService.onTargetEvent(
                "target-evt-1", 1001L, "ORDER_PAID", task.getTargetEventTime());

        var scriptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var executeAtCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), scriptCaptor.capture(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                eq("target-evt-1"), executeAtCaptor.capture());
        assertThat(executeAtCaptor.getValue()).isGreaterThanOrEqualTo(System.currentTimeMillis() - 1000);
        assertThat(scriptCaptor.getValue()).contains("ZSCORE", "ZADD");
    }

    /**
     * 验证重复事件不会重新调度已结束归因任务。
     */
    @Test
    @DisplayName("duplicate target event does not requeue MATCHED or EXPIRED task")
    void duplicateTargetEventDoesNotRequeueTerminalTask() {
        doThrow(new DuplicateKeyException("Duplicate target_event_id"))
                .when(attributionTaskMapper).insert(any());

        for (String terminalStatus : List.of("MATCHED", "EXPIRED")) {
            AttributionTask task = pendingTask();
            task.setStatus(terminalStatus);
            when(attributionTaskMapper.selectOne(any())).thenReturn(task);

            attributionService.onTargetEvent(
                    "target-evt-1", 1001L, "ORDER_PAID", task.getTargetEventTime());
        }

        verify(redissonClient, never()).getScript(any(Codec.class));
        verify(redissonClient, never()).getScoredSortedSet(anyString());
    }

    /**
     * 验证抢占使用原子的 pending 到 processing Lua 脚本。
     */
    @Test
    @DisplayName("atomic claim keeps pending-to-processing Lua contract")
    void claimUsesAtomicPendingToProcessingScript() {
        RScript script = mock(RScript.class);
        when(redissonClient.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.MULTI), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                anyLong(), eq(100))).thenReturn(List.of("target-evt-1"));

        Set<String> claimed = attributionService.claimExpiredTasks();

        assertThat(claimed).containsExactly("target-evt-1");
        var scriptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), scriptCaptor.capture(),
                eq(RScript.ReturnType.MULTI), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                anyLong(), eq(100));
        assertThat(scriptCaptor.getValue()).contains("ZRANGEBYSCORE", "ZREM", "ZADD");
    }

    /**
     * 验证临时归因失败后可以重试并成功。
     */
    @Test
    @DisplayName("transient attribution failure can be retried and then completes")
    void transientAttributionFailureCanBeRetried() {
        AttributionTask task = pendingTask();
        when(attributionTaskMapper.selectOne(any())).thenReturn(task);
        ClickEvent click = clickAt(LocalDateTime.of(2026, 8, 6, 10, 0, 0), 500L);
        when(clickEventMapper.selectList(any()))
                .thenThrow(new RuntimeException("temporary click store failure"))
                .thenReturn(List.of(click));
        when(deliveryRecordMapper.selectOne(any()))
                .thenReturn(deliverySentAt(LocalDateTime.of(2026, 8, 6, 9, 0, 0), 500L));
        when(attributionRecordMapper.insert(any())).thenReturn(1);

        assertThatCode(() -> attributionService.executeAttribution("target-evt-1"))
                .isInstanceOf(RuntimeException.class);
        attributionService.executeAttribution("target-evt-1");

        verify(attributionRecordMapper).insert(any(AttributionRecord.class));
        verify(attributionTaskMapper).update(eq(null), any());
    }

    /**
     * 验证首次 Redis 调度仅在数据库提交后发生。
     */
    @Test
    @DisplayName("initial attribution scheduling is invisible before DB commit")
    void initialSchedulingRunsOnlyAfterCommit() {
        RScript script = mock(RScript.class);
        when(redissonClient.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                eq("target-evt-1"), anyLong())).thenReturn(1L);

        TransactionTemplate transactionTemplate = new TransactionTemplate(new TestTransactionManager());
        transactionTemplate.execute(status -> {
            attributionService.onTargetEvent(
                    "target-evt-1", 1001L, "ORDER_PAID",
                    LocalDateTime.of(2026, 8, 6, 12, 0, 0));

            // The INSERT has happened, but the transaction has not committed;
            // Redis must still be untouched at this exact synchronization point.
            verifyNoInteractions(redissonClient);
            return null;
        });

        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                eq("target-evt-1"), anyLong());
    }

    /**
     * 验证数据库事务回滚时不发布归因任务。
     */
    @Test
    @DisplayName("rolled back attribution insert never publishes to Redis")
    void rolledBackSchedulingIsNotPublished() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(new TestTransactionManager());
        transactionTemplate.execute(status -> {
            attributionService.onTargetEvent(
                    "target-evt-1", 1001L, "ORDER_PAID",
                    LocalDateTime.of(2026, 8, 6, 12, 0, 0));
            status.setRollbackOnly();
            return null;
        });

        verifyNoInteractions(redissonClient);
    }

    /**
     * 验证暂缺数据库任务被识别为可重试情况。
     */
    @Test
    @DisplayName("a claimed task missing from MySQL is retryable, not silent success")
    void missingTaskIsRetryable() {
        when(attributionTaskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> attributionService.executeAttribution("target-evt-1"))
                .isInstanceOf(AttributionTaskNotFoundException.class)
                .hasMessageContaining("target-evt-1");
        verifyNoInteractions(clickEventMapper, attributionRecordMapper, deliveryRecordMapper);
    }

    /**
     * 验证 Redis 故障后的对账可以恢复数据库 PENDING 任务。
     */
    @Test
    @DisplayName("reconciliation restores a PENDING task after a post-commit Redis failure")
    void reconciliationRestoresPendingTaskAfterRedisFailure() {
        AttributionTask task = pendingTask();
        when(attributionTaskMapper.selectList(any())).thenReturn(List.of(task));

        RScript script = mock(RScript.class);
        when(redissonClient.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                eq("target-evt-1"), anyLong()))
                .thenThrow(new RuntimeException("temporary Redis outage"))
                .thenReturn(1L);

        assertThat(attributionService.reconcilePendingTasks()).isZero();
        assertThat(attributionService.reconcilePendingTasks()).isEqualTo(1);
        verify(script, times(2)).eval(eq(RScript.Mode.READ_WRITE), anyString(),
                eq(RScript.ReturnType.INTEGER), eq(List.of(
                        "delay:attribution", "delay:attribution:processing")),
                eq("target-evt-1"), anyLong());
    }

    /**
     * 验证终态任务重复处理为幂等成功。
     */
    @Test
    @DisplayName("terminal task is an idempotent execution success")
    void terminalTaskIsIdempotentSuccess() {
        AttributionTask task = pendingTask();
        task.setStatus("MATCHED");
        when(attributionTaskMapper.selectOne(any())).thenReturn(task);

        assertThat(attributionService.executeAttribution("target-evt-1"))
                .isEqualTo(AttributionService.ExecutionResult.ALREADY_TERMINAL);
        verifyNoInteractions(clickEventMapper, attributionRecordMapper, deliveryRecordMapper);
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        /**
         * 提供测试事务对象，用于驱动真实 Spring 事务同步回调。
         */
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        /**
         * 实现无实际数据库操作的测试事务开启钩子。
         */
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource is needed; this manager exists only to exercise
            // Spring's real synchronization/afterCommit lifecycle.
        }

        /**
         * 实现测试提交钩子，使事务同步器触发 afterCommit。
         */
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        /**
         * 实现测试回滚钩子，用于断言回滚后不发布任务。
         */
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
