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

    /**
     * 将 Kafka 事件上下文转换为事件实体，保留原始、接收和有效时间，并序列化业务属性。
     */
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

    /**
     * 提取事件上下文的 properties 映射，缺失或类型不符时返回空映射。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getProperties(Map<String, Object> eventMap) {
        Object props = eventMap.get("properties");
        if (props instanceof Map) {
            return (Map<String, Object>) props;
        }
        return Collections.emptyMap();
    }

    /**
     * 解析已存储的属性 JSON；空文本、空对象或解析失败均按空属性处理。
     */
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

    /**
     * 序列化事件属性；输入为空或序列化失败时回退为 JSON 空对象。
     */
    private String propsToJson(Object props) {
        if (props == null) return "{}";
        try {
            return OBJECT_MAPPER.writeValueAsString(props);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 兼容省略秒数的 ISO 本地时间与空格分隔时间；null 使用当前时间，格式非法时继续抛出异常。
     */
    private LocalDateTime parseDateTime(String dt) {
        if (dt == null) return LocalDateTime.now();
        // LocalDateTime.toString() 在秒数为 0 时会省略秒（例如
        // "2026-08-23T23:00"），有小数秒时则会保留。ISO_LOCAL_DATE_TIME
        // 能同时解析这两种格式；这里也兼容历史补偿消息使用的空格分隔符。
        String normalized = dt.trim().replace(' ', 'T');
        return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 转换数值或整数字符串，null 按零处理；不吞掉非法数值异常。
     */
    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(String.valueOf(val));
    }

    /**
     * 转换可选目标 ID，保留 null 与空白文本为 null，非法非空文本抛出解析异常。
     */
    private Long toNullableLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        String text = String.valueOf(val).trim();
        return text.isEmpty() ? null : Long.parseLong(text);
    }

    /**
     * Result of Phase 1 persistence.
     */
    public static class PersistResult {
        private final boolean ok;
        private final UserEvent event;
        private final Map<String, Object> context;

        /**
         * 组合持久化结果、事件实体及供后续画像和决策使用的上下文。
         */
        private PersistResult(boolean ok, UserEvent event, Map<String, Object> context) {
            this.ok = ok;
            this.event = event;
            this.context = context;
        }

        /**
         * 构造成功结果，携带已确定的事件事实和标准化处理上下文。
         */
        static PersistResult ok(UserEvent event, Map<String, Object> context) {
            return new PersistResult(true, event, context);
        }

        /**
         * 构造失败结果，事件和上下文均为空，通知消费者不要继续业务处理。
         */
        static PersistResult fail() {
            return new PersistResult(false, null, null);
        }

        /**
         * 返回事件持久化阶段是否成功。
         */
        public boolean isOk() { return ok; }
        /**
         * 返回持久化阶段确定的事件实体；失败结果中为 null。
         */
        public UserEvent getEvent() { return event; }
        /**
         * 返回后续处理应使用的事件上下文；失败结果中为 null。
         */
        public Map<String, Object> getContext() { return context; }
    }
}
