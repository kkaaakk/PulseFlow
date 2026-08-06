package com.pulseflow.common.util;

/**
 * 延迟任务类型常量。
 *
 * <p>历史上 {@code DelayedTaskExecutor}、{@code DecisionEngine}、
 * {@code DelayTaskRecoveryJob} 各自手写字符串 "DELAYED_CAMPAIGN" / "DEFAULT"，
 * 导致恢复 Job 扫错 ZSET、永远找不到卡住的任务。统一为共享常量避免再次漂移。</p>
 */
public final class DelayedTaskConstants {

    /** 营销延迟任务（如购物车放弃召回）的统一 taskType。 */
    public static final String CAMPAIGN_TASK_TYPE = "DELAYED_CAMPAIGN";

    private DelayedTaskConstants() {}
}
