package com.pulseflow.boot.agenttools;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 按 Java 登录操作员实施本进程并发与速率准入。
 * Bounded local admission, scoped to the authoritative Java operator. */
final class AgentGatewayLimit {
    private final Semaphore slots = new Semaphore(4);
    private final Map<Long, ArrayDeque<Long>> starts = new HashMap<>();

    /** 按操作员和全局容量申请调用名额，超限立即拒绝；返回的资源关闭时归还并发名额。 */
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

    /** 构造统一的容量拒绝错误，避免向客户端暴露内部限流细节。 */
    private AgentGatewayException rejected() {
        return new AgentGatewayException(429, "agent_capacity_exceeded");
    }
}
