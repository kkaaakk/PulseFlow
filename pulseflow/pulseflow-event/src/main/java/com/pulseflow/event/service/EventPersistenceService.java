package com.pulseflow.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseflow.entity.UserEvent;
import com.pulseflow.entity.UserMetricHourly;
import com.pulseflow.mapper.UserEventMapper;
import com.pulseflow.mapper.UserMetricHourlyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在独立事务中保存事件与小时统计，重复事件复用数据库规范事实。
 *
 * Persists an event and its hourly metric bucket inside a single MySQL transaction.
 *
 * <p>Placed in a dedicated {@code @Service} (instead of inside {@code EventConsumer})
 * so that Spring's proxy-based {@code @Transactional} actually takes effect — the
 * previous self-invocation from {@code EventConsumer.consume()} silently disabled
 * the transaction, breaking the "event + metric bucket share one transaction"
 * invariant required by the design doc.</p>
 *
 * <p>When a {@link DuplicateKeyException} is caught it means the event was already
 * persisted on a previous attempt that may have crashed before Redis/decision phases
 * finished. Per the design, we do NOT trust the Kafka replay payload — we load the
 * canonical event from MySQL and return it so downstream phases reuse the DB value.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPersistenceService {

    private final UserEventMapper userEventMapper;
    private final UserMetricHourlyMapper userMetricHourlyMapper;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * 同事务保存事件与小时统计；重复事件读取数据库规范事实，避免重复累加。
     *
     * Persist event + hourly metric in one transaction.
     *
     * @return result holding the canonical event (freshly inserted or loaded from DB on duplicate)
     *         and the normalized context map to feed Redis/decision phases; {@code ok=false} on
     *         unexpected failure (caller should NOT ack Kafka in that case).
     */
    @Transactional(rollbackFor = Exception.class)
    public PersistResult persist(Map<String, Object> rawEventMap) {
        String eventId = (String) rawEventMap.get("eventId");
        try {
            UserEvent event = buildUserEvent(rawEventMap);
            userEventMapper.insert(event);

            upsertMetricHourlyAtomic(event, rawEventMap);

            return PersistResult.ok(event, buildContextFromEvent(event));
        } catch (DuplicateKeyException e) {
            // Event already in MySQL — load canonical record and continue with it.
            log.info("Duplicate event {}, loading canonical record from DB", eventId);
            UserEvent existing = userEventMapper.selectOne(
                    new LambdaQueryWrapper<UserEvent>()
                            .eq(UserEvent::getEventId, eventId));
            if (existing == null) {
                // Should not happen, but be safe.
                return PersistResult.fail();
            }
            return PersistResult.ok(existing, buildContextFromEvent(existing));
        } catch (Exception e) {
            log.error("Phase 1 persist failed for event {}: {}", eventId, e.getMessage(), e);
            return PersistResult.fail();
        }
    }

    /**
     * 以原子 upsert 累加小时桶，保证并发消费和回放下统计一致。
     *
     * Atomic upsert via INSERT ... ON DUPLICATE KEY UPDATE so concurrent
     * consumers / replays accumulate correctly without a select-then-write race.
     */
    private void upsertMetricHourlyAtomic(UserEvent event, Map<String, Object> rawEventMap) {
        LocalDateTime metricHour = event.getEffectiveEventTime()
                .withMinute(0).withSecond(0).withNano(0);

        Map<String, Object> props = getProperties(rawEventMap);
        long duration = 0L;
        BigDecimal amount = BigDecimal.ZERO;
        if (props != null) {
            if (props.containsKey("duration")) {
                duration = toLong(props.get("duration"));
            }
            if (props.containsKey("price")) {
                amount = new BigDecimal(String.valueOf(props.get("price")));
            }
        }

        userMetricHourlyMapper.upsertAccumulate(
                event.getUserId(),
                metricHour,
                event.getEventType(),
                1,
                duration,
                amount);
    }

    /**
     * 从数据库规范事件重建上下文，使画像和决策不依赖重复消息中的变化字段。
     *
     * Rebuild a normalized context map from the canonical DB event so Phase 2/3
     * always use MySQL-sourced values (not the Kafka replay payload).
     */
    private Map<String, Object> buildContextFromEvent(UserEvent event) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("eventId", event.getEventId());
        ctx.put("userId", event.getUserId());
        ctx.put("eventType", event.getEventType());
        ctx.put("targetId", event.getTargetId());
        ctx.put("effectiveEventTime", event.getEffectiveEventTime().toString());
        ctx.put("properties", parseProperties(event.getProperties()));
        return ctx;
    }

    /** 将接入映射转换为事件实体，规范化标识、时间和属性。 */
    private UserEvent buildUserEvent(Map<String, Object> eventMap) {
        return UserEvent.builder()
                .eventId((String) eventMap.get("eventId"))
                .userId(toLong(eventMap.get("userId")))
                .eventType((String) eventMap.get("eventType"))
                .targetId(toNullableLong(eventMap.get("targetId")))
                .eventTime(parseDateTime((String) eventMap.get("eventTime")))
                .receivedAt(parseDateTime((String) eventMap.get("receivedAt")))
                .effectiveEventTime(parseDateTime((String) eventMap.get("effectiveEventTime")))
                .clockSkew(Boolean.TRUE.equals(eventMap.get("clockSkew")) ? 1 : 0)
                .properties(propsToJson(eventMap.get("properties")))
                .build();
    }

    /** 读取事件上下文中的扩展属性，兼容后续统计所需的数据结构。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getProperties(Map<String, Object> eventMap) {
        Object props = eventMap.get("properties");
        if (props instanceof Map) {
            return (Map<String, Object>) props;
        }
        return Collections.emptyMap();
    }

    /** 将数据库保存的属性 JSON 还原为映射。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseProperties(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** 将接入属性统一序列化为数据库存储格式。 */
    private String propsToJson(Object props) {
        if (props == null) return "{}";
        try {
            return OBJECT_MAPPER.writeValueAsString(props);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 解析事件时间字符串，供规范事件构建使用。 */
    private LocalDateTime parseDateTime(String dt) {
        if (dt == null) return LocalDateTime.now();
        // LocalDateTime.toString() 在秒数为 0 时会省略秒（例如
        // "2026-08-23T23:00"），有小数秒时则会保留。ISO_LOCAL_DATE_TIME
        // 能同时解析这两种格式；这里也兼容历史补偿消息使用的空格分隔符。
        String normalized = dt.trim().replace(' ', 'T');
        return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** 把必需数值字段转换为 Long。 */
    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(String.valueOf(val));
    }

    /** 转换可选数值字段，保留 null 而不误当成零。 */
    private Long toNullableLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        String text = String.valueOf(val).trim();
        return text.isEmpty() ? null : Long.parseLong(text);
    }

    /**
     * 事件落库阶段的结果，供后续画像与决策阶段复用规范事实。
     *
     * Result of Phase 1 persistence.
     */
    public static class PersistResult {
        private final boolean ok;
        private final UserEvent event;
        private final Map<String, Object> context;

        /** 保存落库是否成功、数据库规范事件及后续阶段使用的上下文。 */
        private PersistResult(boolean ok, UserEvent event, Map<String, Object> context) {
            this.ok = ok;
            this.event = event;
            this.context = context;
        }

        /** 构造成功结果，携带规范事件和画像、决策所需的上下文。 */
        static PersistResult ok(UserEvent event, Map<String, Object> context) {
            return new PersistResult(true, event, context);
        }

        /** 构造落库失败结果，使消费流程停止后续业务阶段。 */
        static PersistResult fail() {
            return new PersistResult(false, null, null);
        }

        /** 返回落库阶段是否完成，供消费者决定是否继续。 */
        public boolean isOk() { return ok; }
        /** 返回首次落库或重复查询得到的数据库规范事件。 */
        public UserEvent getEvent() { return event; }
        /** 返回由规范事件生成的上下文，供实时画像和决策复用。 */
        public Map<String, Object> getContext() { return context; }
    }
}
