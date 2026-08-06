package com.pulseflow.job.handler;

import com.pulseflow.campaign.delay.DelayedTaskManager;
import com.pulseflow.common.util.DelayedTaskConstants;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DelayTaskRecoveryJob {

    private final DelayedTaskManager delayedTaskManager;

    @XxlJob("delayTaskRecoveryJob")
    public void execute() {
        log.info("DelayTaskRecoveryJob started");

        // Recover tasks stuck in processing for more than 5 minutes.
        // 必须与 DelayedTaskExecutor / DecisionEngine 使用同一个 taskType，
        // 否则恢复 Job 扫描错误的 ZSET，永远找不到卡住的任务。
        long timeoutMillis = 5 * 60 * 1000;
        List<String> recovered = delayedTaskManager.recoverStuckTasks(
                DelayedTaskConstants.CAMPAIGN_TASK_TYPE, timeoutMillis);

        if (!recovered.isEmpty()) {
            log.info("Recovered {} stuck delayed tasks", recovered.size());
        }

        log.info("DelayTaskRecoveryJob completed");
    }
}
