package com.pulseflow.boot.agenttools;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Bounded local admission, scoped to the authoritative Java operator. */
final class AgentGatewayLimit {
    private final Semaphore slots = new Semaphore(4);
    private final Map<Long, ArrayDeque<Long>> starts = new HashMap<>();

    /**
     * 清理一分钟前的调用记录，检查单用户每分钟六次与全局并发配额。成功返回必须关闭的许可；超额抛出 429，用户记录最多保留 1024 份。
     */
    synchronized AutoCloseable acquire(Long operator) {
        long now = System.nanoTime();
        long minute = 60_000_000_000L;
        starts.values().forEach(times -> { while (!times.isEmpty() && now - times.peek() >= minute) times.remove(); });
        starts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (!starts.containsKey(operator) && starts.size() >= 1024) throw rejected();
        ArrayDeque<Long> times = starts.computeIfAbsent(operator, ignored -> new ArrayDeque<>());
        if (times.size() >= 6 || !slots.tryAcquire()) throw rejected();
        times.add(now);
        return slots::release;
    }

    /**
     * 构造统一的容量不足异常，供并发限制和速率限制共用。
     */
    private AgentGatewayException rejected() {
        return new AgentGatewayException(429, "agent_capacity_exceeded");
    }
}
