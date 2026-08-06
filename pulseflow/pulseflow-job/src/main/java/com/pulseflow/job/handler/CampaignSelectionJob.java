package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.campaign.decision.DecisionEngine;
import com.pulseflow.common.enums.CampaignStatus;
import com.pulseflow.common.enums.ExecutionStatus;
import com.pulseflow.common.enums.TriggerType;
import com.pulseflow.entity.Campaign;
import com.pulseflow.entity.CampaignExecution;
import com.pulseflow.entity.UserProfile;
import com.pulseflow.mapper.CampaignExecutionMapper;
import com.pulseflow.mapper.CampaignMapper;
import com.pulseflow.mapper.UserProfileMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

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

    @XxlJob("campaignSelectionJob")
    public void execute() {
        log.info("CampaignSelectionJob started");

        // Step 0: Recover stuck RUNNING instances (built-in recovery, no separate RecoveryJob)
        recoverStuckExecutions();

        // Phase A: Create execution instances for due SCHEDULED campaigns
        List<Campaign> activeScheduled = campaignMapper.selectList(
                new LambdaQueryWrapper<Campaign>()
                        .eq(Campaign::getStatus, CampaignStatus.ACTIVE.name())
                        .eq(Campaign::getTriggerType, TriggerType.SCHEDULED.name())
                        .le(Campaign::getNextTriggerAt, LocalDateTime.now()));

        for (Campaign campaign : activeScheduled) {
            try {
                createExecutionInstance(campaign);
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

    private void recoverStuckExecutions() {
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);

        // Recover stuck RUNNING → PENDING (under retry limit)
        campaignExecutionMapper.update(null,
                new LambdaUpdateWrapper<CampaignExecution>()
                        .eq(CampaignExecution::getStatus, ExecutionStatus.RUNNING.name())
                        .lt(CampaignExecution::getStartedAt, tenMinutesAgo)
                        .lt(CampaignExecution::getRetryCount, 3)
                        .setSql("retry_count = retry_count + 1")
                        .set(CampaignExecution::getStatus, ExecutionStatus.PENDING.name()));

        // Mark over-retry as FAILED
        campaignExecutionMapper.update(null,
                new LambdaUpdateWrapper<CampaignExecution>()
                        .eq(CampaignExecution::getStatus, ExecutionStatus.RUNNING.name())
                        .lt(CampaignExecution::getStartedAt, tenMinutesAgo)
                        .ge(CampaignExecution::getRetryCount, 3)
                        .set(CampaignExecution::getStatus, ExecutionStatus.FAILED.name()));
    }

    private void createExecutionInstance(Campaign campaign) {
        LocalDateTime scheduledAt = campaign.getNextTriggerAt();

        // Optimistic lock: advance next_trigger_at (cron-based) + bump version
        LocalDateTime nextTriggerAt = computeNextTriggerAt(campaign, scheduledAt);
        int updated = campaignMapper.update(null,
                new LambdaUpdateWrapper<Campaign>()
                        .eq(Campaign::getId, campaign.getId())
                        .eq(Campaign::getVersion, campaign.getVersion())
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
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_campaign_schedule already exists for this slot — skip.
            log.info("Execution instance already exists for campaign {} at {}", campaign.getId(), scheduledAt);
        }
    }

    /**
     * Compute the next trigger time from the cron expression.
     * Falls back to +1 hour if the cron is missing/invalid so the campaign
     * doesn't fire-storm, while logging a warning.
     */
    private LocalDateTime computeNextTriggerAt(Campaign campaign, LocalDateTime scheduledAt) {
        String cron = campaign.getCronExpression();
        if (cron == null || cron.isBlank()) {
            log.warn("Campaign {} has no cron_expression, defaulting next_trigger_at to +1h",
                    campaign.getId());
            return scheduledAt.plusHours(1);
        }
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

    private void executeCampaign(CampaignExecution execution) {
        // CAS claim PENDING → RUNNING (only one node proceeds)
        int claimed = campaignExecutionMapper.update(null,
                new LambdaUpdateWrapper<CampaignExecution>()
                        .eq(CampaignExecution::getId, execution.getId())
                        .eq(CampaignExecution::getStatus, ExecutionStatus.PENDING.name())
                        .set(CampaignExecution::getStatus, ExecutionStatus.RUNNING.name())
                        .set(CampaignExecution::getStartedAt, LocalDateTime.now()));

        if (claimed != 1) {
            return;
        }

        try {
            Campaign campaign = campaignMapper.selectById(execution.getCampaignId());
            if (campaign == null || !CampaignStatus.ACTIVE.name().equals(campaign.getStatus())) {
                log.info("Campaign {} no longer active, marking execution {} done",
                        execution.getCampaignId(), execution.getId());
                campaignExecutionMapper.update(null,
                        new LambdaUpdateWrapper<CampaignExecution>()
                                .eq(CampaignExecution::getId, execution.getId())
                                .set(CampaignExecution::getStatus, ExecutionStatus.DONE.name())
                                .set(CampaignExecution::getFinishedAt, LocalDateTime.now()));
                return;
            }

            // Audience selection + rule evaluation + delivery task creation.
            // Candidates = all active users; evaluateBatch applies campaign rules
            // and dedup_key uk prevents duplicate delivery per (execution, user).
            int scanned = 0;
            long lastId = 0L;
            int pageSize = 500;
            while (true) {
                List<UserProfile> page = userProfileMapper.selectList(
                        new LambdaQueryWrapper<UserProfile>()
                                .eq(UserProfile::getStatus, 1)
                                .gt(UserProfile::getId, lastId)
                                .orderByAsc(UserProfile::getId)
                                .last("LIMIT " + pageSize));
                if (page.isEmpty()) break;

                for (UserProfile up : page) {
                    scanned++;
                    // Creates a delivery task only when rules match;
                    // dedup_key = {executionId}:{userId} guards duplicates.
                    decisionEngine.evaluateBatch(campaign, up.getUserId(), execution.getId());
                }
                lastId = page.get(page.size() - 1).getId();
                if (page.size() < pageSize) break;
            }

            campaignExecutionMapper.update(null,
                    new LambdaUpdateWrapper<CampaignExecution>()
                            .eq(CampaignExecution::getId, execution.getId())
                            .set(CampaignExecution::getStatus, ExecutionStatus.DONE.name())
                            .set(CampaignExecution::getFinishedAt, LocalDateTime.now()));

            log.info("Campaign execution {} done: campaignId={}, scanned={} users",
                    execution.getId(), execution.getCampaignId(), scanned);
        } catch (Exception e) {
            log.error("Campaign execution {} failed: {}", execution.getId(), e.getMessage(), e);
            campaignExecutionMapper.update(null,
                    new LambdaUpdateWrapper<CampaignExecution>()
                            .eq(CampaignExecution::getId, execution.getId())
                            .set(CampaignExecution::getStatus, ExecutionStatus.PENDING.name())
                            .set(CampaignExecution::getLastError,
                                    e.getMessage() != null
                                            ? e.getMessage().substring(0, Math.min(512, e.getMessage().length()))
                                            : "Unknown error")
                            .setSql("retry_count = retry_count + 1"));
        }
    }
}
