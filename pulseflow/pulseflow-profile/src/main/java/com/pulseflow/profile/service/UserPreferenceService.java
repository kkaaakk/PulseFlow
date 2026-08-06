package com.pulseflow.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.entity.AttributionRecord;
import com.pulseflow.mapper.AttributionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 快速预过滤：免打扰 / 已退订 / 已转化。
 *
 * <p>设计 §3.1 要求决策命中后、创建触达任务前做快速预过滤（非原子判断，
 * 真正的频控在发送时执行）。三类标记存储方式：</p>
 * <ul>
 *   <li><b>免打扰</b>：Redis String {@code dnd:user:{userId}} = "1"，
 *       用户级开关，可由管理端设置（MVP 用 Redis 直接写）。</li>
 *   <li><b>已退订</b>：Redis Set {@code unsub:{channel}} 包含 userId，
 *       渠道级退订（如用户退订邮件，则该渠道不再触达）。</li>
 *   <li><b>已转化</b>：查询 {@code attribution_record} 中该 campaign +
 *       userId 是否已有归因记录，有则说明该用户在此活动已转化，不再重复触达。</li>
 * </ul>
 *
 * <p>三类都是 best-effort 快速预判，不影响频控的原子性保证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final RedissonClient redissonClient;
    private final AttributionRecordMapper attributionRecordMapper;

    private static final String DND_KEY_PREFIX = "dnd:user:";
    private static final String UNSUB_KEY_PREFIX = "unsub:";

    /**
     * 判断该用户在该渠道是否可触达（同时检查免打扰 + 渠道退订 + 活动已转化）。
     *
     * @return true 表示通过预过滤，可创建触达任务
     */
    public boolean canDeliver(Long userId, Long campaignId, String channel) {
        if (isDnd(userId)) {
            log.info("Pre-filter skip: user {} in DND", userId);
            return false;
        }
        if (isUnsubscribed(userId, channel)) {
            log.info("Pre-filter skip: user {} unsubscribed channel {}", userId, channel);
            return false;
        }
        if (isAlreadyConverted(userId, campaignId)) {
            log.info("Pre-filter skip: user {} already converted for campaign {}", userId, campaignId);
            return false;
        }
        return true;
    }

    /** 免打扰：Redis String 标记，存在即开启。 */
    public boolean isDnd(Long userId) {
        RBucket<String> bucket = redissonClient.getBucket(DND_KEY_PREFIX + userId);
        return bucket.isExists();
    }

    /** 设置/取消免打扰。 */
    public void setDnd(Long userId, boolean enabled) {
        RBucket<String> bucket = redissonClient.getBucket(DND_KEY_PREFIX + userId);
        if (enabled) {
            bucket.set("1");
        } else {
            bucket.delete();
        }
    }

    /** 渠道退订：Redis Set 成员判断。 */
    public boolean isUnsubscribed(Long userId, String channel) {
        RSet<Long> set = redissonClient.getSet(UNSUB_KEY_PREFIX + channel);
        return set.contains(userId);
    }

    /** 订阅/退订渠道。 */
    public void setUnsubscribed(Long userId, String channel, boolean unsubscribed) {
        RSet<Long> set = redissonClient.getSet(UNSUB_KEY_PREFIX + channel);
        if (unsubscribed) {
            set.add(userId);
        } else {
            set.remove(userId);
        }
    }

    /** 活动已转化：查 attribution_record 是否已有该用户+活动的归因。 */
    public boolean isAlreadyConverted(Long userId, Long campaignId) {
        Long count = attributionRecordMapper.selectCount(
                new LambdaQueryWrapper<AttributionRecord>()
                        .eq(AttributionRecord::getUserId, userId)
                        .eq(AttributionRecord::getCampaignId, campaignId));
        return count != null && count > 0;
    }
}
