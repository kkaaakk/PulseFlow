package com.pulseflow.campaign.profile;

import com.pulseflow.common.util.CartEvent;
import com.pulseflow.entity.UserEvent;
import com.pulseflow.mapper.UserEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Atomically updates core realtime state and marks events processed for seven days. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeProfileUpdateService {

    private final RedissonClient redissonClient;
    private final UserEventMapper userEvents;

    /** 事件处理标记 TTL（7 天），≥ Kafka 保留期，防止重放窗口内重复执行。 */
    private static final long PROCESSED_FLAG_TTL_SECONDS = 604800;
    private static final DateTimeFormatter REDIS_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

    /**
     * 完整的实时画像 Lua 脚本。正常消费 Phase 2 与补偿重放共用此脚本。
     *
     * <p>返回值语义：</p>
     * <ul>
     *   <li>1 — 首次执行，状态已更新</li>
     *   <li>0 — 已处理过（processed 标记存在），幂等跳过</li>
     * </ul>
     */
    private static final String REALTIME_PROFILE_LUA = """
            local processedKey = KEYS[1]
            local rtKey = KEYS[2]
            local dailyKey = KEYS[3]
            local cartKey = KEYS[4]
            local eventType = ARGV[1]
            local effectiveTime = ARGV[2]
            local cartItemId = ARGV[3]
            local cartItemJson = ARGV[4]
            local ttl = tonumber(ARGV[5])
            local revision = ARGV[6]
            local cartAction = ARGV[7]

            -- 幂等：已处理过直接跳过
            if redis.call('EXISTS', processedKey) == 1 then
                return 0
            end

            -- Redis serializes scripts but does not roll back earlier writes on errors.
            local active = eventType == 'CONTENT_VIEW' or eventType == 'ADD_CART'
            local lastActive = active and redis.call('HGET', rtKey, 'last_active_at')
            local applyCart = false
            if revision ~= '' then
                local current = redis.call('GET', KEYS[5])
                applyCart = not current or revision >= current
            end

            if eventType == 'CONTENT_VIEW' then
                redis.call('HINCRBY', dailyKey, 'views', 1)
                redis.call('EXPIRE', dailyKey, 172800)
            end

            if applyCart then
                if cartAction == 'ADD_CART' then
                    redis.call('HSET', cartKey, cartItemId, cartItemJson)
                else
                    redis.call('HDEL', cartKey, cartItemId)
                end
                redis.call('SET', KEYS[5], revision, 'EX', ttl)
            end

            if active and (not lastActive or effectiveTime > lastActive) then
                redis.call('HSET', rtKey, 'last_active_at', effectiveTime)
            end

            -- 设置处理标记（7 天 TTL ≥ Kafka 保留期）
            redis.call('SET', processedKey, '1', 'EX', ttl)

            return 1
            """;

    /**
     * 执行实时画像更新（幂等）。
     *
     * @param ctx 事件上下文，须包含 eventId / userId / eventType / effectiveEventTime / properties
     * @return true 表示执行成功（首次更新或幂等跳过均视为成功）；false 表示 Redis 调用失败
     */
    public boolean update(Map<String, Object> ctx) {
        String eventId = (String) ctx.get("eventId");
        try {
            String eventType = (String) ctx.get("eventType");
            Long userId = toLong(ctx.get("userId"));
            String time = (String) ctx.get("effectiveEventTime");
            LocalDateTime effectiveTime = time == null ? LocalDateTime.now() : parseEventTime(time);
            String dateStr = effectiveTime.format(DateTimeFormatter.BASIC_ISO_DATE);

            String cartItemId = switch (eventType) {
                case "ADD_CART", "REMOVE_CART", "ORDER_PAID" -> CartEvent.itemId(getProperties(ctx));
                default -> null;
            };
            UserEvent latest = cartItemId == null ? null : userEvents.selectLatestCartMutation(userId, cartItemId);
            String revision = latest == null ? "" : cartRevision(latest);
            String action = latest == null ? "" : latest.getEventType();
            String cartItemJson = latest == null ? "" : latest.getProperties();

            String processedKey = "event:processed:" + eventId;
            String rtKey = "user:rt:" + userId;
            String dailyKey = "user:daily:" + userId + ":" + dateStr;
            String cartKey = "user:cart:" + userId;
            String revisionKey = "user:cart-version:" + userId + ":" + cartItemId;

            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            Long result = script.eval(RScript.Mode.READ_WRITE,
                    REALTIME_PROFILE_LUA,
                    RScript.ReturnType.INTEGER,
                    List.of(processedKey, rtKey, dailyKey, cartKey, revisionKey),
                    eventType, effectiveTime.format(REDIS_TIME), cartItemId == null ? "" : cartItemId,
                    cartItemJson, PROCESSED_FLAG_TTL_SECONDS, revision, action);

            // result == 0 表示已处理过（幂等跳过），同样视为成功。
            return result != null && (result == 0 || result == 1);
        } catch (Exception e) {
            log.warn("Realtime profile update failed for event {} ({})", eventId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Same ordering as the MySQL index. Keep BIGINT ordering exact in Lua. */
    private String cartRevision(UserEvent event) {
        return event.getEffectiveEventTime().format(REDIS_TIME)
                + ("ADD_CART".equals(event.getEventType()) ? "0" : "1")
                + String.format(Locale.ROOT, "%020d", event.getId());
    }

    /**
     * 从事件上下文取得属性映射；缺失或类型不符时返回 null，供调用方跳过属性相关更新。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getProperties(Map<String, Object> ctx) {
        Object props = ctx.get("properties");
        if (props instanceof Map) {
            return (Map<String, Object>) props;
        }
        return null;
    }

    /**
     * Parses the canonical event time used by Kafka replay and EventPersistence.
     * ISO_LOCAL_DATE_TIME accepts both second precision and fractional seconds;
     * the latter is emitted by LocalDateTime.toString() for live events.
     */
    static LocalDateTime parseEventTime(String normalizedTime) {
        return LocalDateTime.parse(normalizedTime.trim().replace(' ', 'T'),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 转换事件上下文中的数值或整数字符串，null 按零处理，非法文本由解析异常向外传播。
     */
    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(String.valueOf(val));
    }
}
