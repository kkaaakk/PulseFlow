package com.pulseflow.campaign.profile;

import com.pulseflow.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 实时画像 Redis 更新的单一事实来源。
 *
 * <p>历史上 {@code EventConsumer}（Phase 2）和 {@code CompensationJob}（重放）
 * 各自维护一份 Lua 脚本，且补偿版本只更新 {@code last_active_at}，导致 Redis
 * 真正故障后补偿无法完整重建实时状态——直接破坏了简历亮点"Redis 故障补偿恢复"。</p>
 *
 * <p>本类抽出完整的 Lua 实现（按事件类型更新 last_login_at / views / search_count /
 * 购物车），正常消费与补偿重放调用同一份脚本，避免再次漂移。</p>
 *
 * <p>幂等保证：{@code event:processed:{eventId}} 标记位（7 天 TTL ≥ Kafka 保留期）
 * 在脚本内 EXISTS 判断，重复执行安全。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeProfileUpdateService {

    private final RedissonClient redissonClient;

    /** 事件处理标记 TTL（7 天），≥ Kafka 保留期，防止重放窗口内重复执行。 */
    private static final long PROCESSED_FLAG_TTL_SECONDS = 604800;

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

            -- 幂等：已处理过直接跳过
            if redis.call('EXISTS', processedKey) == 1 then
                return 0
            end

            -- 按事件类型更新对应 Key
            if eventType == 'LOGIN' then
                redis.call('HSET', rtKey, 'last_login_at', effectiveTime)
            elseif eventType == 'CONTENT_VIEW' then
                redis.call('HINCRBY', dailyKey, 'views', 1)
            elseif eventType == 'SEARCH' then
                redis.call('HINCRBY', dailyKey, 'search_count', 1)
            elseif eventType == 'ADD_CART' then
                redis.call('HSET', cartKey, cartItemId, cartItemJson)
            elseif eventType == 'REMOVE_CART' then
                redis.call('HDEL', cartKey, cartItemId)
            elseif eventType == 'ORDER_PAID' then
                redis.call('HDEL', cartKey, cartItemId)
            end

            -- 统一更新最后活跃时间
            redis.call('HSET', rtKey, 'last_active_at', effectiveTime)

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
            String effectiveTime = normalizeTime((String) ctx.get("effectiveEventTime"));
            String dateStr = LocalDateTime.parse(effectiveTime,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            Map<String, Object> props = getProperties(ctx);
            String cartItemId = props != null ? String.valueOf(props.getOrDefault("cartItemId", "")) : "";
            String cartItemJson = props != null ? JsonUtil.toJson(props) : "{}";

            String processedKey = "event:processed:" + eventId;
            String rtKey = "user:rt:" + userId;
            String dailyKey = "user:daily:" + userId + ":" + dateStr;
            String cartKey = "user:cart:" + userId;

            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            Long result = script.eval(RScript.Mode.READ_WRITE,
                    REALTIME_PROFILE_LUA,
                    RScript.ReturnType.INTEGER,
                    List.of(processedKey, rtKey, dailyKey, cartKey),
                    eventType, effectiveTime, cartItemId, cartItemJson, PROCESSED_FLAG_TTL_SECONDS);

            // result == 0 表示已处理过（幂等跳过），同样视为成功。
            return result != null && (result == 0 || result == 1);
        } catch (Exception e) {
            log.error("Realtime profile update failed for event {}: {}", eventId, e.getMessage(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getProperties(Map<String, Object> ctx) {
        Object props = ctx.get("properties");
        if (props instanceof Map) {
            return (Map<String, Object>) props;
        }
        return null;
    }

    private String normalizeTime(String time) {
        if (time == null) {
            return LocalDateTime.now().toString().replace("T", " ");
        }
        return time.contains("T") ? time.replace("T", " ") : time;
    }

    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(String.valueOf(val));
    }
}
