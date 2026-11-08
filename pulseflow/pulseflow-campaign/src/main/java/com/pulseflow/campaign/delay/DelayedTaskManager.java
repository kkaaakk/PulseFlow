package com.pulseflow.campaign.delay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DelayedTaskManager {

    private final RedissonClient redissonClient;

    private static final String PENDING_PREFIX = "delay:pending:";
    private static final String PROCESSING_PREFIX = "delay:processing:";

    /**
     * Add a delayed task to the pending ZSET.
     * Score = execution timestamp (epoch millis).
     */
    public void addDelayedTask(String taskType, String taskId, long executeAtMillis) {
        queueScript().eval(RScript.Mode.READ_WRITE, """
                if redis.call('ZSCORE', KEYS[2], ARGV[1]) then return 0 end
                return redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
                """, RScript.ReturnType.INTEGER,
                List.of(PENDING_PREFIX + taskType, PROCESSING_PREFIX + taskType), taskId, executeAtMillis);
    }

    /**
     * Atomically claim tasks from pending to processing using Lua.
     * Returns list of task IDs that were successfully claimed.
     */
    public List<String> claimTasks(String taskType, long currentTimeMillis, int limit) {
        String luaScript = """
            local pendingKey = KEYS[1]
            local processingKey = KEYS[2]
            local now = tonumber(ARGV[1])
            local batchSize = tonumber(ARGV[2])
            
            local tasks = redis.call('ZRANGEBYSCORE', pendingKey, 0, now, 'LIMIT', 0, batchSize)
            if #tasks == 0 then
                return {}
            end
            
            for i, taskId in ipairs(tasks) do
                -- Preserve the source if writing the destination fails.
                redis.call('ZADD', processingKey, now, taskId)
                redis.call('ZREM', pendingKey, taskId)
            end
            
            return tasks
            """;

        RScript script = queueScript();
        List<Object> result = script.eval(RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.MULTI,
                List.of(PENDING_PREFIX + taskType, PROCESSING_PREFIX + taskType),
                currentTimeMillis, limit);

        if (result == null) return Collections.emptyList();
        return result.stream().map(Object::toString).toList();
    }

    /**
     * Remove a task from processing ZSET after completion.
     */
    public void completeTask(String taskType, String taskId) {
        redissonClient.getScoredSortedSet(PROCESSING_PREFIX + taskType).remove(taskId);
    }

    /** A timed-out owner must not remove a new claim of the same member. */
    public void completeTask(String taskType, String taskId, long claimedAtMillis) {
        queueScript().eval(RScript.Mode.READ_WRITE, """
                local owner = redis.call('ZSCORE', KEYS[1], ARGV[1])
                if not owner or tonumber(owner) ~= tonumber(ARGV[2]) then return 0 end
                return redis.call('ZREM', KEYS[1], ARGV[1])
                """, RScript.ReturnType.INTEGER, List.of(PROCESSING_PREFIX + taskType), taskId, claimedAtMillis);
    }

    /**
     * Recover tasks stuck in processing (timeout).
     * Move tasks from processing back to pending if they've been processing too long.
     */
    public List<String> recoverStuckTasks(String taskType, long timeoutMillis) {
        String luaScript = """
            local processingKey = KEYS[1]
            local pendingKey = KEYS[2]
            local timeout = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            
            local stuck = redis.call('ZRANGEBYSCORE', processingKey, 0, now - timeout, 'LIMIT', 0, 500)
            if #stuck == 0 then
                return {}
            end
            
            for i, taskId in ipairs(stuck) do
                redis.call('ZADD', pendingKey, now, taskId)
                redis.call('ZREM', processingKey, taskId)
            end
            
            return stuck
            """;

        RScript script = queueScript();
        List<Object> result = script.eval(RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.MULTI,
                List.of(PROCESSING_PREFIX + taskType, PENDING_PREFIX + taskType),
                timeoutMillis, System.currentTimeMillis());

        if (result == null) return Collections.emptyList();
        return result.stream().map(Object::toString).toList();
    }
    /** Keep the existing serialized members; only Lua score and limit arguments are plain text. */
    private RScript queueScript() {
        var memberCodec = redissonClient.getConfig().getCodec();
        return redissonClient.getScript(new BaseCodec() {
            @Override
            public Decoder<Object> getValueDecoder() {
                return memberCodec.getValueDecoder();
            }

            @Override
            public Encoder getValueEncoder() {
                return value -> (value instanceof Number ? StringCodec.INSTANCE : memberCodec)
                        .getValueEncoder().encode(value);
            }
        });
    }
}
