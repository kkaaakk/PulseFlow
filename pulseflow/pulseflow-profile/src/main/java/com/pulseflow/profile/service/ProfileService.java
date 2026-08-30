package com.pulseflow.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final RedissonClient redissonClient;
    private final UserBehaviorSummaryMapper behaviorSummaryMapper;
    private final UserTagMapper userTagMapper;

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
        result.putAll(dailyMap.readAllMap());

        // Cart items
        RMap<String, String> cartMap = redissonClient.getMap("user:cart:" + userId, StringCodec.INSTANCE);
        result.put("cart_count", String.valueOf(cartMap.size()));
        result.put("cart_items", cartMap.readAllMap().toString());

        return result;
    }

    /**
     * Get window metrics (cached in Redis, fallback to MySQL).
     *
     * <p>MySQL fallback 按 calculated_at DESC 排序，<b>必须用 putIfAbsent</b>：
     * 同一 metric_type 有多条历史记录，先遍历到的是最新值，putIfAbsent 保留它
     * 不被后续旧值覆盖。之前用 put 导致最终留下的是最旧值，决策引擎读到陈旧数据。</p>
     */
    public Map<String, Object> getWindowMetrics(Long userId) {
        String cacheKey = "user:window:" + userId;
        RMap<String, Object> cacheMap = redissonClient.getMap(cacheKey);

        if (cacheMap.isExists()) {
            Map<String, Object> cached = new HashMap<>(cacheMap.readAllMap());
            if (!cached.isEmpty()) {
                return cached;
            }
        }

        // Fallback to MySQL：按 calculated_at DESC，putIfAbsent 保留最新值
        List<UserBehaviorSummary> summaries = behaviorSummaryMapper.selectList(
                new LambdaQueryWrapper<UserBehaviorSummary>()
                        .eq(UserBehaviorSummary::getUserId, userId)
                        .orderByDesc(UserBehaviorSummary::getCalculatedAt));

        Map<String, Object> result = new HashMap<>();
        for (UserBehaviorSummary s : summaries) {
            result.putIfAbsent(s.getMetricType(), s.getMetricValue());
        }

        // Cache in Redis
        if (!result.isEmpty()) {
            cacheMap.putAll(result);
            cacheMap.expire(3600, TimeUnit.SECONDS);
        }

        return result;
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

    /**
     * Get specific metric value for rule evaluation.
     */
    public Long getMetricValue(Long userId, String metricType) {
        Map<String, Object> metrics = getWindowMetrics(userId);
        Object val = metrics.get(metricType);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return 0L;
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
