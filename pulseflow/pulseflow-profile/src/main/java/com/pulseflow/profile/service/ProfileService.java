package com.pulseflow.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.common.util.BehaviorWindow;
import com.pulseflow.entity.UserBehaviorSummary;
import com.pulseflow.entity.UserTag;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final RedissonClient redissonClient;
    private final UserBehaviorSummaryMapper behaviorSummaryMapper;
    private final UserTagMapper userTagMapper;

    private static final String WINDOW_GENERATION = "user:window:generation";
    private static final String WINDOW_CACHE_FIELD = "calendar_snapshot";
    private static final List<String> CURRENT_METRICS =
            List.of(BehaviorWindow.ACTIVE_DAYS, BehaviorWindow.ORDER_COUNT, BehaviorWindow.SPEND);

    /**
     * Get realtime metrics from Redis.
     */
    public Map<String, String> getRealtimeMetrics(Long userId) {
        Map<String, String> result = new HashMap<>();

        // Long-term realtime state
        RMap<String, String> rtMap = redissonClient.getMap("user:rt:" + userId, StringCodec.INSTANCE);
        result.putAll(rtMap.readAllMap());

        // Today's daily counts
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        RMap<String, String> dailyMap = redissonClient.getMap(
                "user:daily:" + userId + ":" + dateStr, StringCodec.INSTANCE);
        String views = dailyMap.get("views");
        if (views != null) result.put("views", views);

        // Cart items
        RMap<String, String> cartMap = redissonClient.getMap("user:cart:" + userId, StringCodec.INSTANCE);
        result.put("cart_count", String.valueOf(cartMap.size()));
        result.put("cart_items", cartMap.readAllMap().toString());

        return result;
    }

    /** Read the current calendar snapshot for the console, with MySQL fallback. */
    public Map<String, Object> getWindowMetrics(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        String version = null;
        RMap<String, Object> cache = null;
        try {
            String generation = redissonClient.<String>getBucket(WINDOW_GENERATION, StringCodec.INSTANCE).get();
            if (generation != null) {
                version = generation + ":" + now.toLocalDate();
                cache = redissonClient.getMap("user:window:" + userId);
                Object entry = cache.get(WINDOW_CACHE_FIELD);
                if (entry instanceof Map<?, ?> snapshot && version.equals(snapshot.get("version"))) {
                    Map<String, Object> result = new HashMap<>();
                    for (String type : CURRENT_METRICS) {
                        result.put(type, snapshot.get(type));
                    }
                    return result;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Window cache read failed for user {}", userId, e);
            cache = null;
        }

        Map<String, BigDecimal> current = readCurrentWindowMetrics(List.of(userId), now)
                .getOrDefault(userId, Map.of());
        Map<String, Object> result = new HashMap<>();
        for (String type : CURRENT_METRICS) {
            result.put(type, current.getOrDefault(type, BigDecimal.ZERO));
        }
        if (cache != null) {
            Map<String, Object> snapshot = new HashMap<>(result);
            snapshot.put("version", version);
            try {
                cache.fastPut(WINDOW_CACHE_FIELD, snapshot);
                cache.expire(5, TimeUnit.MINUTES);
            } catch (RuntimeException e) {
                log.debug("Window cache write failed for user {}", userId, e);
            }
        }
        return result;
    }

    /** The job advances the cache generation only after its MySQL transaction commits. */
    public void invalidateWindowCaches() {
        try {
            redissonClient.<String>getBucket(WINDOW_GENERATION, StringCodec.INSTANCE)
                    .set(UUID.randomUUID().toString());
        } catch (RuntimeException e) {
            log.warn("Window cache generation could not be refreshed; cached entries expire in five minutes", e);
        }
    }

    /** Decision and preview read the same current-day snapshot directly in bounded pages. */
    public Map<Long, Map<String, BigDecimal>> readCurrentWindowMetrics(List<Long> userIds, LocalDateTime now) {
        Map<Long, Map<String, BigDecimal>> result = new HashMap<>();
        if (userIds.isEmpty()) return result;
        BehaviorWindow window = new BehaviorWindow(now.toLocalDate());
        for (UserBehaviorSummary row : behaviorSummaryMapper.selectCurrentWindows(
                userIds, window.today().atStartOfDay(), now, window.endExclusive())) {
            result.computeIfAbsent(row.getUserId(), id -> new HashMap<>())
                    .put(row.getMetricType(), row.getMetricValue() == null ? BigDecimal.ZERO : row.getMetricValue());
        }
        return result;
    }

    public static String ruleMetricType(String type) {
        return switch (type) {
            case "activeDays7d", "active_7d" -> BehaviorWindow.ACTIVE_DAYS;
            case "orderCount30d" -> BehaviorWindow.ORDER_COUNT;
            case "spend30d" -> BehaviorWindow.SPEND;
            default -> type;
        };
    }

    public static boolean isCurrentMetric(String type) {
        return CURRENT_METRICS.contains(type);
    }

    /**
     * Get user tags.
     */
    public List<UserTag> getUserTags(Long userId) {
        return userTagMapper.selectList(
                new LambdaQueryWrapper<UserTag>()
                        .eq(UserTag::getUserId, userId)
                        .orderByDesc(UserTag::getCalculatedAt));
    }

    /** Retain the integral accessor for existing callers. */
    public Long getMetricValue(Long userId, String metricType) {
        return getRuleMetric(userId, metricType).longValue();
    }

    public BigDecimal getRuleMetric(Long userId, String metricType) {
        String type = ruleMetricType(metricType);
        if (isCurrentMetric(type)) {
            return readCurrentWindowMetrics(List.of(userId), LocalDateTime.now())
                    .getOrDefault(userId, Map.of()).getOrDefault(type, BigDecimal.ZERO);
        }
        // Existing stored rules can still access the last value of a retired metric.
        UserBehaviorSummary latest = behaviorSummaryMapper.selectOne(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getUserId, userId)
                        .eq(UserBehaviorSummary::getMetricType, type)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt)
                        .last("LIMIT 1"));
        return latest == null || latest.getMetricValue() == null ? BigDecimal.ZERO : latest.getMetricValue();
    }

    /**
     * Check if user has a specific tag (latest value must be "1").
     *
     * <p>标签策略无论命中与否都会插入记录（值为 "1" 或 "0"），且表保留历史
     * （uk = user_id + tag_name + calculated_at）。因此不能仅判断记录是否存在，
     * 必须取最新一条并检查 tagValue == "1"，否则曾经命中过但已不命中的用户
     * 仍会被误判为拥有该标签。</p>
     */
    public boolean hasTag(Long userId, String tagName) {
        UserTag tag = userTagMapper.selectOne(
                new LambdaQueryWrapper<UserTag>()
                        .eq(UserTag::getUserId, userId)
                        .eq(UserTag::getTagName, tagName)
                        .orderByDesc(UserTag::getCalculatedAt)
                        .last("LIMIT 1"));
        return tag != null && "1".equals(tag.getTagValue());
    }
}
