package com.pulseflow.event.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseflow.campaign.attribution.AttributionService;
import com.pulseflow.campaign.decision.DecisionEngine;
import com.pulseflow.campaign.profile.RealtimeProfileUpdateService;
import com.pulseflow.common.enums.CompensationTaskType;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.UserEvent;
import com.pulseflow.event.service.EventPersistenceService;
import com.pulseflow.mapper.DataCompensationTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Consumes raw behavior events from {@code pulseflow.raw.events} in three phases:
 * <ol>
 *   <li><b>MySQL transaction</b> (delegated to {@link EventPersistenceService} so
 *       {@code @Transactional} is honored — previously broken by self-invocation):
 *       insert {@code user_event} + upsert {@code user_metric_hourly} atomically.
 *       On {@code DuplicateKeyException} the canonical event is loaded from DB.</li>
 *   <li><b>Redis Lua</b> atomic realtime metric update with a 7-day processed flag.
 *       实现委托给 {@link RealtimeProfileUpdateService}，与 CompensationJob 重放
 *       共用同一份 Lua，避免漂移。</li>
 *   <li><b>Decision engine</b> evaluates the event against active EVENT campaigns.
 *       Target events (e.g. ORDER_PAID) also seed the attribution waiting task.</li>
 * </ol>
 *
 * <p><b>ACK rule</b>: ack only when Phase 1 succeeded (or duplicate = already
 * persisted), or when a failed Phase 2/3 was durably recorded as a compensation
 * task. Any other failure throws to let Kafka re-deliver.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventConsumer {

    private final EventPersistenceService eventPersistenceService;
    private final DataCompensationTaskMapper compensationTaskMapper;
    private final RealtimeProfileUpdateService realtimeProfileUpdateService;
    private final DecisionEngine decisionEngine;
    private final AttributionService attributionService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** Events that represent a conversion goal and therefore seed attribution. */
    private static final Set<String> ATTRIBUTION_TARGET_EVENTS = Set.of("ORDER_PAID");

    /** 依次完成事务落库、实时画像和决策；后两阶段失败时持久化补偿再结束消费。 */
    @KafkaListener(topics = "pulseflow.raw.events", groupId = "pulseflow-event-group",
            concurrency = "4")
    public void consume(ConsumerRecord<String, String> record) {
        String eventJson = record.value();
        Map<String, Object> rawEventMap = parseEvent(eventJson);
        String eventId = (String) rawEventMap.get("eventId");

        log.info("Consuming event: eventId={}, offset={}", eventId, record.offset());

        // 第一阶段由独立 Spring 服务开启事务，事件事实与小时统计必须一起提交。
        // Phase 1: MySQL transaction (event + hourly metric, single tx, atomic upsert)
        EventPersistenceService.PersistResult phase1 =
                eventPersistenceService.persist(rawEventMap);
        if (!phase1.isOk()) {
            // MySQL transaction failed — do NOT ack, let Kafka re-deliver.
            throw new RuntimeException("Phase 1 failed for event: " + eventId);
        }

        // 重复消息可能携带不同属性；后续阶段只能读取首次落库的规范事实，避免画像与统计分歧。
        // Downstream phases use the canonical context sourced from MySQL,
        // never the raw Kafka replay payload (MySQL is the single source of truth).
        Map<String, Object> ctx = phase1.getContext();
        UserEvent canonical = phase1.getEvent();

        // Phase 2: Redis Lua atomic update (委托共享服务，与补偿重放共用同一份 Lua)
        boolean phase2Ok = executePhase2(ctx);

        // Redis 更新成功后再执行决策，避免使用尚未收敛的画像创建触达。
        // Phase 3: Decision engine (only if Redis state is consistent)
        boolean phase3Ok = false;
        if (phase2Ok) {
            phase3Ok = executePhase3(ctx);
        }

        // 目标事件另行创建归因等待任务；此处归因异常仅记录日志，不作为 EVENT_REPLAY 补偿内容。
        // Attribution seeding for target (conversion) events. Best-effort: its own
        // delay queue + DB persistence handle reliability; a failure here does not
        // block the event pipeline (it is logged, not compensated as EVENT_REPLAY).
        if (phase2Ok && ATTRIBUTION_TARGET_EVENTS.contains(ctx.get("eventType"))) {
            try {
                attributionService.onTargetEvent(
                        canonical.getEventId(),
                        canonical.getUserId(),
                        canonical.getEventType(),
                        canonical.getEffectiveEventTime());
            } catch (Exception e) {
                log.error("Attribution seeding failed for target event {}: {}",
                        eventId, e.getMessage(), e);
            }
        }

        // 画像或决策失败时，必须先确认补偿落库；补偿也写失败则抛异常，交由 Kafka 重新投递。
        // If Phase 2 or 3 failed, durably record a compensation task then ack.
        if (!phase2Ok || !phase3Ok) {
            boolean compensationWritten = writeCompensationTask(ctx);
            if (!compensationWritten) {
                // Cannot persist — do NOT ack, let Kafka re-deliver.
                throw new RuntimeException(
                        "Failed to write compensation task for event: " + eventId);
            }
            log.info("Event {} partially processed, compensation task recorded", eventId);
            return;
        }

        log.info("Event {} fully processed", eventId);
    }

    /**
     * 委托共享实时画像服务执行幂等 Lua 更新，与补偿回放保持相同口径。
     *
     * Phase 2: Redis Lua atomic update for realtime metrics.
     * 委托给 {@link RealtimeProfileUpdateService}，与 CompensationJob 重放共用同一份 Lua。
     */
    private boolean executePhase2(Map<String, Object> ctx) {
        return realtimeProfileUpdateService.update(ctx);
    }

    /**
     * 调用决策引擎匹配活动，基础设施错误由上层记录补偿任务。
     *
     * Phase 3: Evaluate rules and create delivery tasks via DecisionEngine.
     * 基础设施异常（DB / Redis / Kafka）会从 DecisionEngine 向外抛，由上层写补偿任务。
     */
    private boolean executePhase3(Map<String, Object> ctx) {
        try {
            decisionEngine.evaluate(ctx);
            return true;
        } catch (Exception e) {
            log.error("Phase 3 decision failed for event {}: {}",
                    ctx.get("eventId"), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 原子保存 EVENT_REPLAY 补偿载荷，重复任务恢复为 PENDING 以重新执行。
     *
     * Durably record an EVENT_REPLAY compensation task (atomic upsert).
     * On duplicate (event_id, task_type) the row is restored to PENDING.
     */
    private boolean writeCompensationTask(Map<String, Object> ctx) {
        String eventId = (String) ctx.get("eventId");
        try {
            compensationTaskMapper.upsertPendingRestore(
                    eventId,
                    CompensationTaskType.EVENT_REPLAY.name(),
                    JsonUtil.toJson(ctx),
                    "phase2_or_phase3_failed");
            return true;
        } catch (Exception e) {
            log.error("Failed to write compensation task for event {}: {}",
                    eventId, e.getMessage(), e);
            return false;
        }
    }

    // --- Helpers ---

    /** 将 Kafka JSON 载荷解析为事件上下文，供各处理阶段使用。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEvent(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse event JSON", e);
        }
    }
}
