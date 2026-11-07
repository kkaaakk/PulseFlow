package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.campaign.decision.DecisionEngine;
import com.pulseflow.common.enums.CampaignStatus;
import com.pulseflow.common.enums.ExecutionStatus;
import com.pulseflow.common.enums.TriggerType;
import com.pulseflow.common.util.BehaviorWindow;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignExecution;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.mapper.CampaignExecutionMapper;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.UserBehaviorSummaryMapper;
import com.pulseflow.mapper.UserProfileMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignSelectionJob {

    private final CampaignMapper campaignMapper;
    private final CampaignExecutionMapper campaignExecutionMapper;
    private final DecisionEngine decisionEngine;
    private final UserProfileMapper userProfileMapper;
    private final UserBehaviorSummaryMapper behaviorSummaryMapper;
    private final TransactionTemplate transactionTemplate;

    private static final int PAGE_SIZE = 500;
    private static final int MAX_RETRIES = 3;

    /**
     * 先恢复卡住的执行实例，再为到期活动建立实例，最后按计划时间处理最多十个 PENDING 实例。
     */
    @XxlJob("campaignSelectionJob")
    public void execute() {
        log.info("CampaignSelectionJob started");

        // Step 0: Recover stuck RUNNING instances (built-in recovery, no separate RecoveryJob)
        recoverStuckExecutions();

        // Phase A: Create execution instances for due SCHEDULED campaigns
        LocalDateTime now = LocalDateTime.now();
        BehaviorWindow window = new BehaviorWindow(now.toLocalDate());
        if (!behaviorSummaryMapper.readyForRecall(window.activityStart(), window.today().atStartOfDay(),
                now, window.endExclusive())) {
            log.info("Deferring SCHEDULED campaigns until today's activity snapshot is available");
            return;
        }
        List<Campaign> activeScheduled = campaignMapper.selectList(
                new LambdaQueryWrapper<Campaign>()
                        .eq(Campaign::getStatus, CampaignStatus.ACTIVE.name())
                        .eq(Campaign::getTriggerType, TriggerType.SCHEDULED.name())
                        .le(Campaign::getNextTriggerAt, now)
                        .and(q -> q.isNull(Campaign::getStartTime).or().le(Campaign::getStartTime, now))
                        .and(q -> q.isNull(Campaign::getEndTime).or().ge(Campaign::getEndTime, now)));

        for (Campaign campaign : activeScheduled) {
            try {
                transactionTemplate.executeWithoutResult(status -> createExecutionInstance(campaign));
            } catch (Exception e) {
                log.error("Failed to create execution instance for campaign {}: {}",
                        campaign.getId(), e.getMessage());
            }
        }

        // Phase B: Execute all PENDING execution instances (new + recovered)
        List<CampaignExecution> pendingExecutions = campaignExecutionMapper.selectList(
                new LambdaQueryWrapper<CampaignExecution>()
                        .eq(CampaignExecution::getStatus, ExecutionStatus.PENDING.name())
                        .orderByAsc(CampaignExecution::getScheduledAt)
                        .last("LIMIT 10"));

        for (CampaignExecution execution : pendingExecutions) {
            try {
                executeCampaign(execution);
            } catch (Exception e) {
                log.error("Failed to execute campaign {}: {}",
                        execution.getCampaignId(), e.getMessage());
            }
        }

        log.info("CampaignSelectionJob completed: {} due campaigns, {} pending executions",
                activeScheduled.size(), pendingExecutions.size());
    }

    /**
     * 恢复运行超过十分钟且未满三次重试的实例为 PENDING，已达上限的实例标记 FAILED。
     */
    private void recoverStuckExecutions() {
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);

        // Recover stuck RUNNING → PENDING (under retry limit)
        campaignExecutionMapper.update(null,
                new LambdaUpdateWrapper<CampaignExecution>()
                        .eq(CampaignExecution::getStatus, ExecutionStatus.RUNNING.name())
                        .lt(CampaignExecution::getStartedAt, tenMinutesAgo)
                        .lt(CampaignExecution::getRetryCount, MAX_RETRIES)
                        .setSql("retry_count = retry_count + 1")
                        .set(CampaignExecution::getLastError, "execution lease expired")
                        .set(CampaignExecution::getStatus, ExecutionStatus.PENDING.name()));

        // Mark over-retry as FAILED
        campaignExecutionMapper.update(null,
                new LambdaUpdateWrapper<CampaignExecution>()
                        .eq(CampaignExecution::getStatus, ExecutionStatus.RUNNING.name())
                        .lt(CampaignExecution::getStartedAt, tenMinutesAgo)
                        .ge(CampaignExecution::getRetryCount, MAX_RETRIES)
                        .set(CampaignExecution::getLastError, "execution lease expired")
                        .set(CampaignExecution::getFinishedAt, LocalDateTime.now())
                        .set(CampaignExecution::getStatus, ExecutionStatus.FAILED.name()));
    }

    /**
     * 用版本号条件更新抢占活动的本次计划时间并推进下次时间；再写入执行实例，重复计划槽由唯一键去重。
     */
    private void createExecutionInstance(Campaign campaign) {
        LocalDateTime scheduledAt = campaign.getNextTriggerAt();

        // Optimistic lock: advance next_trigger_at (cron-based) + bump version
        LocalDateTime nextTriggerAt = computeNextTriggerAt(campaign, scheduledAt);
        int updated = campaignMapper.update(null,
                new LambdaUpdateWrapper<Campaign>()
                        .eq(Campaign::getId, campaign.getId())
                        .eq(Campaign::getVersion, campaign.getVersion())
                        .eq(Campaign::getStatus, CampaignStatus.ACTIVE.name())
                        .eq(Campaign::getNextTriggerAt, scheduledAt)
                        .set(Campaign::getNextTriggerAt, nextTriggerAt)
                        .set(Campaign::getLastTriggerAt, LocalDateTime.now())
                        .setSql("version = version + 1"));

        if (updated != 1) {
            log.info("Campaign {} already claimed by another instance", campaign.getId());
            return;
        }

        // Create execution instance for THIS scheduled run
        CampaignExecution execution = CampaignExecution.builder()
                .campaignId(campaign.getId())
                .scheduledAt(scheduledAt)
                .status(ExecutionStatus.PENDING.name())
                .retryCount(0)
                .build();

        try {
            campaignExecutionMapper.insert(execution);
            log.info("Created execution instance: campaignId={}, scheduledAt={}",
                    campaign.getId(), scheduledAt);
        } catch (DuplicateKeyException e) {
            // uk_campaign_schedule already exists for this slot — skip.
            log.info("Execution instance already exists for campaign {} at {}", campaign.getId(), scheduledAt);
        }
    }

    /**
     * Compute the next trigger time from the cron expression.
     * A missing cron denotes a one-time campaign, including confirmed Agent drafts.
     */
    private LocalDateTime computeNextTriggerAt(Campaign campaign, LocalDateTime scheduledAt) {
        String cron = campaign.getCronExpression();
        if (cron == null || cron.isBlank()) return null;
        try {
            CronExpression cronExp = CronExpression.parse(cron);
            LocalDateTime next = cronExp.next(scheduledAt);
            if (next != null) return next;
            // No future occurrence (e.g. past end bound) — push far to avoid re-fire loop.
            log.warn("Campaign {} cron produced no next time after {}, defaulting +1h",
                    campaign.getId(), scheduledAt);
            return scheduledAt.plusHours(1);
        } catch (IllegalArgumentException e) {
            log.error("Campaign {} invalid cron '{}', defaulting +1h: {}",
                    campaign.getId(), cron, e.getMessage());
            return scheduledAt.plusHours(1);
        }
    }

    /** A retry starts scanning from the first page. Delivery keys are stable per execution and user. */
    private void executeCampaign(CampaignExecution execution) {
        int claimed = campaignExecutionMapper.update(null,
                new LambdaUpdateWrapper<CampaignExecution>()
                        .eq(CampaignExecution::getId, execution.getId())
                        .eq(CampaignExecution::getStatus, ExecutionStatus.PENDING.name())
                        .eq(CampaignExecution::getRetryCount, execution.getRetryCount())
                        .set(CampaignExecution::getStatus, ExecutionStatus.RUNNING.name())
                        .set(CampaignExecution::getStartedAt, LocalDateTime.now()));

        if (claimed != 1) return;

        try {
            Campaign campaign = campaignMapper.selectById(execution.getCampaignId());
            int scanned = 0;
            if (campaign != null && CampaignStatus.ACTIVE.name().equals(campaign.getStatus())) {
                long lastId = 0L;
                while (true) {
                    List<UserProfile> page = userProfileMapper.selectList(
                            new LambdaQueryWrapper<UserProfile>()
                                    .select(UserProfile::getId, UserProfile::getUserId)
                                    .eq(UserProfile::getStatus, 1)
                                    .gt(UserProfile::getId, lastId)
                                    .orderByAsc(UserProfile::getId)
                                    .last("LIMIT " + PAGE_SIZE));
                    if (page.isEmpty()) break;

                    // Renew the lease and fence a worker whose attempt was already reclaimed.
                    if (campaignExecutionMapper.update(null, ownedAttempt(execution)
                            .set(CampaignExecution::getStartedAt, LocalDateTime.now())) != 1) return;
                    decisionEngine.evaluateBatch(campaign, page.stream().map(UserProfile::getUserId).toList(),
                            execution.getId());
                    scanned += page.size();
                    lastId = page.get(page.size() - 1).getId();
                    if (page.size() < PAGE_SIZE) break;
                }
            }
            campaignExecutionMapper.update(null, ownedAttempt(execution)
                    .set(CampaignExecution::getStatus, ExecutionStatus.DONE.name())
                    .set(CampaignExecution::getFinishedAt, LocalDateTime.now())
                    .set(CampaignExecution::getLastError, null));
            log.info("Campaign execution {} finished: scanned={} users", execution.getId(), scanned);
        } catch (Exception e) {
            log.error("Campaign execution {} failed", execution.getId(), e);
            boolean exhausted = execution.getRetryCount() >= MAX_RETRIES;
            String error = e.getMessage() == null ? "Unknown error" : e.getMessage();
            campaignExecutionMapper.update(null, ownedAttempt(execution)
                    .set(CampaignExecution::getStatus, exhausted ? ExecutionStatus.FAILED.name()
                            : ExecutionStatus.PENDING.name())
                    .set(CampaignExecution::getRetryCount, execution.getRetryCount() + (exhausted ? 0 : 1))
                    .set(CampaignExecution::getFinishedAt, exhausted ? LocalDateTime.now() : null)
                    .set(CampaignExecution::getLastError, error.substring(0, Math.min(512, error.length()))));
        }
    }

    private LambdaUpdateWrapper<CampaignExecution> ownedAttempt(CampaignExecution execution) {
        return new LambdaUpdateWrapper<CampaignExecution>()
                .eq(CampaignExecution::getId, execution.getId())
                .eq(CampaignExecution::getStatus, ExecutionStatus.RUNNING.name())
                .eq(CampaignExecution::getRetryCount, execution.getRetryCount());
    }
}
