package com.pulseflow.campaign.delay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/** 用 pending/processing ZSET 和 Lua 管理延迟任务生命周期。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayedTaskManager {

    private final RedissonClient redissonClient;

    private static final String PENDING_PREFIX = "delay:pending:";
    private static final String PROCESSING_PREFIX = "delay:processing:";

    /**
     * 将任务写入 pending ZSET，以执行时间毫秒值作为 score，同任务 ID 天然去重。
     *
     * Add a delayed task to the pending ZSET.
     * Score = execution timestamp (epoch millis).
     */
    public void addDelayedTask(String taskType, String taskId, long executeAtMillis) {
        String key = PENDING_PREFIX + taskType;
        redissonClient.getScoredSortedSet(key).add(executeAtMillis, taskId);
        log.info("Delayed task added: type={}, id={}, executeAt={}", taskType, taskId, executeAtMillis);
    }

    /**
     * 用 Lua 原子地把到期任务从 pending 移到 processing，避免多实例重复领取。
     *
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
                redis.call('ZREM', pendingKey, taskId)
                redis.call('ZADD', processingKey, now, taskId)
            end
            
            return tasks
            """;

        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.MULTI,
                List.of(PENDING_PREFIX + taskType, PROCESSING_PREFIX + taskType),
                currentTimeMillis, limit);

        if (result == null) return Collections.emptyList();
        return result.stream().map(Object::toString).toList();
    }

    /**
     * 任务完成后移除 processing 标记，防止恢复任务重复安排执行。
     *
     * Remove a task from processing ZSET after completion.
     */
    public void completeTask(String taskType, String taskId) {
        redissonClient.getScoredSortedSet(PROCESSING_PREFIX + taskType).remove(taskId);
    }

    /**
     * 把处理超时的任务移回 pending，返回恢复的任务 ID 以便监控。
     *
     * Recover tasks stuck in processing (timeout).
     * Move tasks from processing back to pending if they've been processing too long.
     */
    public List<String> recoverStuckTasks(String taskType, long timeoutMillis) {
        String luaScript = """
            local processingKey = KEYS[1]
            local pendingKey = KEYS[2]
            local timeout = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            
            local stuck = redis.call('ZRANGEBYSCORE', processingKey, 0, now - timeout)
            if #stuck == 0 then
                return {}
            end
            
            for i, taskId in ipairs(stuck) do
                redis.call('ZREM', processingKey, taskId)
                redis.call('ZADD', pendingKey, now, taskId)
            end
            
            return stuck
            """;

        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.MULTI,
                List.of(PROCESSING_PREFIX + taskType, PENDING_PREFIX + taskType),
                timeoutMillis, System.currentTimeMillis());

        if (result == null) return Collections.emptyList();
        return result.stream().map(Object::toString).toList();
    }
}
