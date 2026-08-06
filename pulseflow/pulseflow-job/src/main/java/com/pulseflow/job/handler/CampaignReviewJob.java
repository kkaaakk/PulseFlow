package com.pulseflow.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseflow.ai.application.CampaignReviewService;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiReview;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scans finished campaigns and triggers AI review generation.
 *
 * <p>Selection criteria (design §10.1):</p>
 * <ul>
 *   <li>endTime in the past (regardless of status string)</li>
 *   <li>no SUCCESS review row exists yet</li>
 *   <li>endTime within the last N hours (avoid re-processing ancient campaigns)</li>
 * </ul>
 *
 * <p>Concurrency (design §7.1.3): each campaign is guarded by a
 * PENDING → PROCESSING CAS lock inside {@link CampaignReviewService#tryGenerate}.
 * If another executor is already processing the same campaign, this job
 * silently skips it — no duplicate AI calls.</p>
 *
 * <p>AI failures are non-fatal: the review row is marked FAILED and the next
 * job run will retry. Performance summary is always persisted even when AI
 * fails.</p>
 *
 * <p>This bean is only assembled when {@code pulseflow.ai.enabled=true}, so
 * AI-disabled deployments don't fail on the missing {@link CampaignReviewService}
 * dependency.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pulseflow.ai", name = "enabled", havingValue = "true")
public class CampaignReviewJob {

    /** Look back window — only review campaigns that ended within this many hours. */
    private static final long LOOK_BACK_HOURS = 72L;

    private final CampaignMapper campaignMapper;
    private final CampaignReviewService reviewService;

    @XxlJob("campaignReviewJob")
    public void execute() {
        log.info("CampaignReviewJob started");
        LocalDateTime since = LocalDateTime.now().minusHours(LOOK_BACK_HOURS);
        String executorId = "job-" + System.getenv().getOrDefault("XXL_JOB_EXECUTOR_ID", "local");

        List<Campaign> candidates = campaignMapper.selectList(
                new LambdaQueryWrapper<Campaign>()
                        .isNotNull(Campaign::getEndTime)
                        .lt(Campaign::getEndTime, LocalDateTime.now())
                        .ge(Campaign::getEndTime, since)
                        .orderByAsc(Campaign::getEndTime));

        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (Campaign c : candidates) {
            try {
                CampaignAiReview existing = reviewService.findLatest(c.getId());
                if (existing != null && isTerminal(existing.getStatus())) {
                    // SUCCESS / SKIPPED_INSUFFICIENT_DATA / PERMANENT_FAILED — no retry
                    skipped++;
                    continue;
                }
                // For RETRYABLE_FAILED and DATA_NOT_READY, respect the backoff
                // window (nextRetryAt) so we don't re-evaluate every job cycle
                // while send data is still aggregating or the provider is
                // cooling down after a retryable failure.
                if (existing != null
                        && ("RETRYABLE_FAILED".equals(existing.getStatus())
                                || "DATA_NOT_READY".equals(existing.getStatus()))
                        && existing.getNextRetryAt() != null
                        && existing.getNextRetryAt().isAfter(LocalDateTime.now())) {
                    skipped++;
                    continue;
                }
                CampaignAiReview result = reviewService.tryGenerate(c.getId(), executorId);
                if (result == null) {
                    // Lock not acquired, or data not ready / insufficient —
                    // tryGenerate returns null for these non-failure skips.
                    skipped++;
                } else if ("SUCCESS".equals(result.getStatus())) {
                    processed++;
                } else if ("DATA_NOT_READY".equals(result.getStatus())) {
                    // Not a failure — data still aggregating, will retry later.
                    skipped++;
                } else {
                    // RETRYABLE_FAILED / SKIPPED_INSUFFICIENT_DATA / PERMANENT_FAILED
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.error("CampaignReviewJob failed for campaignId={}: {}", c.getId(), e.getMessage());
            }
        }
        log.info("CampaignReviewJob finished: processed={}, skipped={}, failed={}, total={}",
                processed, skipped, failed, candidates.size());
    }

    /** Terminal states that should not be re-processed by the job. */
    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status)
                || "SKIPPED_INSUFFICIENT_DATA".equals(status)
                || "PERMANENT_FAILED".equals(status);
    }
}
