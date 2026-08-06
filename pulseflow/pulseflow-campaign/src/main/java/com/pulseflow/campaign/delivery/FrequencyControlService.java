package com.pulseflow.campaign.delivery;

import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.DeliveryTask;
import com.pulseflow.mapper.CampaignMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FrequencyControlService {

    private final RedissonClient redissonClient;
    private final CampaignMapper campaignMapper;

    /** Campaign weekly frequency counter TTL (7 days), per design Redis key plan. */
    private static final int CAMPAIGN_FREQ_TTL_SECONDS = 604800;
    /** Reserved-flag TTL (24h) covers the max retry window so retries don't re-consume quota. */
    private static final int RESERVED_TTL_SECONDS = 86400;
    /** User daily counter TTL (24h). */
    private static final int USER_DAILY_TTL_SECONDS = 86400;
    /** Default limits used only if the campaign row is missing/deleted (fail-open default). */
    private static final int DEFAULT_USER_DAILY_LIMIT = 3;
    private static final int DEFAULT_CAMPAIGN_WEEKLY_LIMIT = 1;

    @Data
    @AllArgsConstructor
    public static class FreqResult {
        private boolean allowed;
        private String reason;

        public static FreqResult ok() { return new FreqResult(true, "OK"); }
        public static FreqResult retryOk() { return new FreqResult(true, "RETRY_OK"); }
        public static FreqResult userLimit() { return new FreqResult(false, "USER_LIMIT"); }
        public static FreqResult campaignLimit() { return new FreqResult(false, "CAMPAIGN_LIMIT"); }
    }

    /**
     * Atomic Lua frequency check + quota reservation.
     * <ul>
     *   <li>Retry tasks already holding {@code freq:reserved:{taskId}} skip the count
     *       (one task consumes quota exactly once across its retry lifecycle).</li>
     *   <li>Limits are read from the campaign config
     *       ({@code user_daily_limit}, {@code campaign_weekly_limit}) — no longer hardcoded.</li>
     * </ul>
     *
     * KEYS[1] = freq:user:{userId}:{date}
     * KEYS[2] = freq:campaign:{campaignId}:{userId}
     * KEYS[3] = freq:reserved:{taskId}
     * ARGV[1] = user daily limit
     * ARGV[2] = campaign weekly limit
     * ARGV[3] = campaign freq TTL (seconds)
     */
    public FreqResult check(DeliveryTask task) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String userKey = "freq:user:" + task.getUserId() + ":" + dateStr;
        String campaignKey = "freq:campaign:" + task.getCampaignId() + ":" + task.getUserId();
        String reservedKey = "freq:reserved:" + task.getId();

        // Read per-campaign limits; fall back to defaults only if the campaign
        // row is gone (the task still exists, so we must make a decision).
        Campaign campaign = campaignMapper.selectById(task.getCampaignId());
        int userDailyLimit = (campaign != null && campaign.getUserDailyLimit() != null)
                ? campaign.getUserDailyLimit() : DEFAULT_USER_DAILY_LIMIT;
        int campaignWeeklyLimit = (campaign != null && campaign.getCampaignWeeklyLimit() != null)
                ? campaign.getCampaignWeeklyLimit() : DEFAULT_CAMPAIGN_WEEKLY_LIMIT;

        String luaScript = """
            local userKey = KEYS[1]
            local campaignKey = KEYS[2]
            local reservedKey = KEYS[3]
            local userLimit = tonumber(ARGV[1])
            local campaignLimit = tonumber(ARGV[2])
            local campaignTtl = tonumber(ARGV[3])

            -- Retry task already consumed quota → allow without re-counting.
            if redis.call('EXISTS', reservedKey) == 1 then
                return {1, 'RETRY_OK'}
            end

            local userCount = tonumber(redis.call('GET', userKey) or 0)
            local campaignCount = tonumber(redis.call('GET', campaignKey) or 0)

            if userCount >= userLimit then
                return {0, 'USER_LIMIT'}
            end

            if campaignCount >= campaignLimit then
                return {0, 'CAMPAIGN_LIMIT'}
            end

            redis.call('INCR', userKey)
            redis.call('INCR', campaignKey)
            redis.call('SET', reservedKey, '1', 'EX', 86400)
            redis.call('EXPIRE', userKey, 86400)
            redis.call('EXPIRE', campaignKey, campaignTtl)

            return {1, 'OK'}
            """;

        try {
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            List<Object> result = script.eval(RScript.Mode.READ_WRITE,
                    luaScript,
                    RScript.ReturnType.MULTI,
                    List.of(userKey, campaignKey, reservedKey),
                    userDailyLimit, campaignWeeklyLimit, CAMPAIGN_FREQ_TTL_SECONDS);

            if (result == null || result.size() < 2) {
                return FreqResult.userLimit(); // fail-safe
            }

            int code = Integer.parseInt(result.get(0).toString());
            String reason = result.get(1).toString();

            if (code == 1) {
                return "RETRY_OK".equals(reason) ? FreqResult.retryOk() : FreqResult.ok();
            }
            return "USER_LIMIT".equals(reason) ? FreqResult.userLimit() : FreqResult.campaignLimit();
        } catch (Exception e) {
            log.error("Frequency control check failed for task {}: {}", task.getId(), e.getMessage(), e);
            return FreqResult.userLimit(); // fail-safe: deny on error
        }
    }
}
