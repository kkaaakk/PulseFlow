package com.pulseflow.ai.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pulseflow.ai.domain.review.ReviewResult;
import com.pulseflow.ai.guardrail.AiOutputParser;
import com.pulseflow.ai.guardrail.ReviewEvidenceValidator;
import com.pulseflow.ai.infrastructure.config.AiFeatureProperties;
import com.pulseflow.ai.infrastructure.observability.AiAuditService;
import com.pulseflow.ai.infrastructure.observability.AiMetrics;
import com.pulseflow.ai.infrastructure.persistence.PerformanceSummaryCalculator;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignAiReview;
import com.pulseflow.ai.infrastructure.persistence.entity.CampaignPerformanceSummary;
import com.pulseflow.ai.infrastructure.persistence.mapper.CampaignAiReviewMapper;
import com.pulseflow.ai.provider.AiModelClient;
import com.pulseflow.ai.provider.AiRequest;
import com.pulseflow.ai.provider.AiResponse;
import com.pulseflow.ai.prompt.CampaignReviewPromptBuilder;
import com.pulseflow.ai.support.AiConflictException;
import com.pulseflow.ai.support.AiErrorCode;
import com.pulseflow.ai.support.AiForbiddenException;
import com.pulseflow.ai.support.AiOutputInvalidException;
import com.pulseflow.ai.support.AiProviderException;
import com.pulseflow.ai.support.AiResourceNotFoundException;
import com.pulseflow.ai.support.AiTaskType;
import com.pulseflow.common.util.JsonUtil;
import com.pulseflow.entity.Campaign;
import com.pulseflow.mapper.CampaignMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI Campaign Review pipeline (design §10 + §7.1.3 concurrency guard).
 *
 * <p>State machine on {@code campaign_ai_review} (UK: campaign_id):</p>
 * <pre>
 *   (absent) --insert PENDING--> PENDING --CAS UPDATE--> PROCESSING --AI--> SUCCESS / FAILED
 *                                                                                    |
 *                                                              (job retry)           |
 *                                                                  PENDING <---------+
 * </pre>
 *
 * <p>The conditional {@code UPDATE ... WHERE status IN ('PENDING','FAILED')}
 * is the compare-and-swap lock: only the first executor that succeeds in
 * transitioning to PROCESSING will call the LLM. Others skip silently.</p>
 *
 * <p>AI failure is non-fatal: the performance summary is preserved, the
 * review row is marked FAILED, and the next job run (or a manual
 * /regenerate call) will retry.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignReviewService {

    /** Status enum values. */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_RETRYABLE_FAILED = "RETRYABLE_FAILED";
    public static final String STATUS_SKIPPED_INSUFFICIENT_DATA = "SKIPPED_INSUFFICIENT_DATA";
    public static final String STATUS_PERMANENT_FAILED = "PERMANENT_FAILED";
    /** sentCount=0 but audience>0 — data might still be aggregating, retryable. */
    public static final String STATUS_DATA_NOT_READY = "DATA_NOT_READY";

    private final CampaignMapper campaignMapper;
    private final CampaignAiReviewMapper reviewMapper;
    private final PerformanceSummaryCalculator summaryCalculator;
    private final CampaignReviewPromptBuilder promptBuilder;
    private final AiModelClient aiModelClient;
    private final AiOutputParser outputParser;
    private final ReviewEvidenceValidator evidenceValidator;
    private final AiAuditService auditService;
    private final AiMetrics aiMetrics;
    private final AiFeatureProperties properties;

    /**
     * Job entry: try to acquire the PROCESSING lock and generate a review.
     *
     * <p>If another executor is already processing this campaign (or a SUCCESS
     * row already exists), returns {@code null} and the caller should skip.</p>
     *
     * @param campaignId target campaign
     * @param executorId XXL-JOB executor id (for lock attribution)
     * @return the persisted review row (status SUCCESS or FAILED), or null if
     *         the lock could not be acquired
     */
    public CampaignAiReview tryGenerate(Long campaignId, String executorId) {
        LockResult lock = tryAcquireLock(campaignId, executorId);
        if (!lock.isAcquired()) {
            log.debug("CampaignReviewService: campaignId={} skipped (lock not acquired, state={})",
                    campaignId, lock.skipReason());
            return null;
        }
        try {
            return doGenerate(campaignId, null, executorId);
        } finally {
            // Terminal state (SUCCESS/RETRYABLE_FAILED/SKIPPED) is set inside
            // doGenerate. If doGenerate threw before setting a terminal state,
            // force RETRYABLE_FAILED so the next job run retries.
            CampaignAiReview row = findLatest(campaignId);
            if (row != null && STATUS_PROCESSING.equals(row.getStatus())) {
                log.warn("CampaignReviewService: campaignId={} left in PROCESSING, forcing RETRYABLE_FAILED",
                        campaignId);
                markRetryableFailed(campaignId, "PROCESS_ABORTED", "process aborted unexpectedly", null);
            }
        }
    }

    /**
     * Manual entry (POST /regenerate): always generate, ignoring any existing
     * SUCCESS state. Still respects the lock — if a job is currently
     * PROCESSING, returns 409.
     *
     * @throws IllegalStateException if another executor is currently PROCESSING
     */
    public CampaignAiReview generate(Long campaignId, Long operatorId) {
        CampaignAiReview existing = findLatest(campaignId);
        String executorId = "manual-" + UUID.randomUUID().toString().substring(0, 8);
        if (existing != null) {
            if (STATUS_PROCESSING.equals(existing.getStatus())) {
                // Check stale lock
                if (!isLockStale(existing)) {
                    throw new AiConflictException(
                            "Campaign " + campaignId + " is currently being processed by "
                                    + existing.getLockedBy());
                }
                log.info("CampaignReviewService: stealing stale lock for campaignId={} (held by {} since {})",
                        campaignId, existing.getLockedBy(), existing.getLockedAt());
            }
            // Force back to PENDING so we can re-acquire (resets retry_count
            // because this is an explicit operator action, not a job retry).
            reviewMapper.update(null,
                    new LambdaUpdateWrapper<CampaignAiReview>()
                            .eq(CampaignAiReview::getCampaignId, campaignId)
                            .set(CampaignAiReview::getStatus, STATUS_PENDING)
                            .set(CampaignAiReview::getRetryable, true)
                            .set(CampaignAiReview::getRetryCount, 0)
                            .set(CampaignAiReview::getNextRetryAt, null)
                            .set(CampaignAiReview::getLockedBy, null)
                            .set(CampaignAiReview::getLockedAt, null));
        }
        LockResult lock = tryAcquireLock(campaignId, executorId);
        if (!lock.isAcquired()) {
            throw new AiConflictException("Failed to acquire lock for campaign " + campaignId);
        }
        CampaignAiReview review = doGenerate(campaignId, operatorId, executorId);
        if (review == null) {
            // doGenerate returns null only when data is insufficient — surface
            // as a 404 so the manual caller knows no review was produced rather
            // than silently returning an empty body.
            throw new AiResourceNotFoundException(
                    "review not generated for campaign " + campaignId + " (insufficient data)");
        }
        return review;
    }

    /**
     * Fetch an existing review. Returns null if absent.
     */
    public CampaignAiReview findLatest(Long campaignId) {
        return reviewMapper.selectOne(
                new LambdaQueryWrapper<CampaignAiReview>()
                        .eq(CampaignAiReview::getCampaignId, campaignId)
                        .last("LIMIT 1"));
    }

    /**
     * Verify that the current operator owns the campaign whose review is being
     * accessed. Throws 403 ({@link AiForbiddenException}) when:
     * <ul>
     *   <li>{@code operatorId} is null (no authenticated session),</li>
     *   <li>the campaign has no {@code created_by} (legacy row — ownership
     *       undefined; default-deny to prevent unauthorized access to
     *       historical data),</li>
     *   <li>the operator does not match {@code campaign.createdBy}.</li>
     * </ul>
     *
     * <p>This is the review-side counterpart of
     * {@code CampaignAiDraftService#requireDraftOwner}. Job-initiated calls
     * bypass this check (they call {@link #tryGenerate} directly).</p>
     */
    public void requireCampaignOwner(Long campaignId, Long operatorId) {
        if (operatorId == null) {
            throw new AiForbiddenException(
                    "Operator ID is required to access review for campaign " + campaignId);
        }
        Campaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            // Consistent with doGenerate: treat missing campaign as 404.
            throw new AiResourceNotFoundException("Campaign not found: " + campaignId);
        }
        if (campaign.getCreatedBy() == null) {
            throw new AiForbiddenException(
                    "Campaign " + campaignId + " has no recorded owner (created_by is null), access denied");
        }
        if (!operatorId.equals(campaign.getCreatedBy())) {
            throw new AiForbiddenException(
                    "Operator " + operatorId + " does not own campaign " + campaignId);
        }
    }

    // ------------------------------------------------------------------
    // Lock management
    // ------------------------------------------------------------------

    /**
     * Try to acquire the PROCESSING lock for a campaign.
     *
     * <p>Strategy:</p>
     * <ol>
     *   <li>If no row exists, INSERT a PENDING row (IGNORE on UK conflict for
     *       concurrent-safety).</li>
     *   <li>Conditional UPDATE: {@code SET status='PROCESSING', locked_by=?,
     *       locked_at=NOW() WHERE campaign_id=? AND status IN ('PENDING','FAILED')
     *       OR (status='PROCESSING' AND locked_at < NOW() - 10min)}.</li>
     *   <li>If affected rows == 1, lock acquired; otherwise skipped.</li>
     * </ol>
     */
    private LockResult tryAcquireLock(Long campaignId, String executorId) {
        // 1. Ensure a row exists (PENDING). INSERT IGNORE handles concurrent inserts.
        CampaignAiReview existing = findLatest(campaignId);
        if (existing == null) {
            CampaignAiReview pending = CampaignAiReview.builder()
                    .campaignId(campaignId)
                    .status(STATUS_PENDING)
                    .lockedBy(null)
                    .lockedAt(null)
                    .version(0)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            try {
                reviewMapper.insert(pending);
            } catch (org.springframework.dao.DuplicateKeyException dke) {
                // Another executor inserted first — fetch it
                existing = findLatest(campaignId);
            }
        }

        if (existing != null && isTerminalSuccess(existing.getStatus())) {
            return LockResult.skipped("already terminal: " + existing.getStatus());
        }

        // 2. Conditional UPDATE — the CAS lock.
        // PENDING, RETRYABLE_FAILED, and DATA_NOT_READY rows are eligible for
        // (re)processing. A stale PROCESSING lock (held beyond lockStaleMinutes)
        // can be stolen.
        int staleMinutes = properties.getReview().getLockStaleMinutes();
        LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(staleMinutes);
        int affected = reviewMapper.update(null,
                new LambdaUpdateWrapper<CampaignAiReview>()
                        .eq(CampaignAiReview::getCampaignId, campaignId)
                        .and(w -> w
                                .in(CampaignAiReview::getStatus,
                                        STATUS_PENDING, STATUS_RETRYABLE_FAILED, STATUS_DATA_NOT_READY)
                                .or(n -> n.eq(CampaignAiReview::getStatus, STATUS_PROCESSING)
                                        .lt(CampaignAiReview::getLockedAt, staleThreshold)))
                        .set(CampaignAiReview::getStatus, STATUS_PROCESSING)
                        .set(CampaignAiReview::getLockedBy, executorId)
                        .set(CampaignAiReview::getLockedAt, LocalDateTime.now()));

        if (affected == 1) {
            return LockResult.acquired();
        }
        return LockResult.skipped("locked by another executor");
    }

    private boolean isLockStale(CampaignAiReview row) {
        if (row.getLockedAt() == null) return true;
        int staleMinutes = properties.getReview().getLockStaleMinutes();
        return row.getLockedAt().isBefore(LocalDateTime.now().minusMinutes(staleMinutes));
    }

    /** Terminal states that should not be re-processed by the job. */
    private boolean isTerminalSuccess(String status) {
        return STATUS_SUCCESS.equals(status)
                || STATUS_SKIPPED_INSUFFICIENT_DATA.equals(status)
                || STATUS_PERMANENT_FAILED.equals(status);
    }

    /**
     * Assess data readiness for a review (design §7.1.5 "数据不足不强生成结论").
     *
     * <p>Three insufficient outcomes are distinguished so the state machine
     * does not permanently skip campaigns whose send data is merely lagging:</p>
     * <ul>
     *   <li>{@code AUDIENCE_ZERO} — {@code targetAudienceCount <= 0}. The
     *       campaign has no target population; nothing will ever change. This
     *       is a permanent skip ({@link #STATUS_SKIPPED_INSUFFICIENT_DATA}).</li>
     *   <li>{@code DATA_NOT_READY} — audience &gt; 0 but {@code sentCount <= 0},
     *       and the campaign ended less than
     *       {@code dataReadyDelayMinutes} ago. Send/conversion pipelines may
     *       still be aggregating. Retryable ({@link #STATUS_DATA_NOT_READY});
     *       the job retries after the grace window.</li>
     *   <li>{@code INSUFFICIENT_DATA} — audience &gt; 0 but {@code sentCount <= 0}
     *       and the grace window has elapsed. The data is genuinely missing,
     *       not merely delayed. Permanent skip.</li>
     * </ul>
     */
    private DataReadiness assessDataReadiness(CampaignPerformanceSummary s, Campaign campaign) {
        if (s == null) {
            return DataReadiness.INSUFFICIENT_DATA;
        }
        if (s.getTargetAudienceCount() == null || s.getTargetAudienceCount() <= 0) {
            return DataReadiness.AUDIENCE_ZERO;
        }
        if (s.getSentCount() == null || s.getSentCount() <= 0) {
            // audience > 0 but no sends recorded yet — could be consumption lag.
            int delayMinutes = properties.getReview().getDataReadyDelayMinutes();
            LocalDateTime readyTime = campaign.getEndTime() == null
                    ? null
                    : campaign.getEndTime().plusMinutes(delayMinutes);
            // No end time → cannot compute grace; treat as not-ready (retryable)
            // so a future job run re-evaluates once the summary is populated.
            if (readyTime == null || LocalDateTime.now().isBefore(readyTime)) {
                return DataReadiness.DATA_NOT_READY;
            }
            return DataReadiness.INSUFFICIENT_DATA;
        }
        return DataReadiness.SUFFICIENT;
    }

    /** Outcome of {@link #assessDataReadiness}. */
    private enum DataReadiness {
        SUFFICIENT,
        AUDIENCE_ZERO,
        DATA_NOT_READY,
        INSUFFICIENT_DATA
    }

    // ------------------------------------------------------------------
    // Core generation (assumes lock is held)
    // ------------------------------------------------------------------

    private CampaignAiReview doGenerate(Long campaignId, Long operatorId, String executorId) {
        Campaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            markPermanentFailed(campaignId, "CAMPAIGN_NOT_FOUND", "Campaign not found: " + campaignId, null);
            throw new AiResourceNotFoundException("Campaign not found: " + campaignId);
        }

        // 1. Compute summary (idempotent)
        CampaignPerformanceSummary summary = summaryCalculator.compute(campaignId);

        // Data-readiness guard (design §7.1.5): never force an AI conclusion
        // when there is no send/audience data to reason about. The model would
        // otherwise fabricate findings from empty inputs. Three distinct
        // insufficient states are handled so delayed send data is retried
        // rather than permanently skipped (see assessDataReadiness).
        DataReadiness readiness = assessDataReadiness(summary, campaign);
        if (readiness == DataReadiness.AUDIENCE_ZERO
                || readiness == DataReadiness.INSUFFICIENT_DATA) {
            log.info("CampaignReviewService: skip review for campaignId={}, insufficient data (sentCount={}, audience={})",
                    campaignId,
                    summary == null ? null : summary.getSentCount(),
                    summary == null ? null : summary.getTargetAudienceCount());
            markSkippedInsufficient(campaignId,
                    summary == null ? null : summary.getId());
            return null;
        }
        if (readiness == DataReadiness.DATA_NOT_READY) {
            log.info("CampaignReviewService: data not ready for campaignId={}, will retry after grace (sentCount={}, audience={}, endTime={})",
                    campaignId,
                    summary.getSentCount(),
                    summary.getTargetAudienceCount(),
                    campaign.getEndTime());
            markDataNotReady(campaignId, summary.getId(), campaign);
            return null;
        }

        // 2. Build LLM input
        Map<String, Object> input = buildLlmInput(campaign, summary);
        String inputJson = JsonUtil.toJson(input);

        // 3. Prompt + call
        CampaignReviewPromptBuilder.BuiltPrompt prompt = promptBuilder.build(input);
        String requestId = "ai_req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        AiRequest request = AiRequest.builder()
                .requestId(requestId)
                .taskType(AiTaskType.REVIEW)
                .systemPrompt(prompt.systemPrompt())
                .userPrompt(prompt.userPrompt())
                .responseSchemaName("ReviewResult")
                .temperature(0.2)
                .maxTokens(2048)
                .metadata(Map.of(
                        "operatorId", String.valueOf(operatorId),
                        "campaignId", String.valueOf(campaignId),
                        "executorId", executorId))
                .build();

        long started = System.currentTimeMillis();
        AiResponse response;
        try {
            response = aiModelClient.generateStructured(request);
        } catch (AiProviderException e) {
            aiMetrics.recordFailure(AiTaskType.REVIEW, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            markRetryableFailed(campaignId, e.getErrorCode(), e.getMessage(), summary.getId());
            throw e;
        }
        aiMetrics.recordRequest(AiTaskType.REVIEW, response.getProvider(),
                Duration.ofMillis(System.currentTimeMillis() - started), true);
        aiMetrics.recordTokens(AiTaskType.REVIEW, response.getProvider(),
                safeInt(response.getPromptTokens()), safeInt(response.getCompletionTokens()));

        // 4. Parse + validate evidence
        ReviewResult result;
        try {
            result = outputParser.parseObject(response.getRawContent(), ReviewResult.class);
            result = evidenceValidator.validate(inputJson, result);
        } catch (AiOutputInvalidException e) {
            aiMetrics.recordFailure(AiTaskType.REVIEW, e.getErrorCode());
            auditService.recordFailure(request, prompt.version(), e.getErrorCode(), e.getMessage());
            markRetryableFailed(campaignId, e.getErrorCode(), e.getMessage(), summary.getId());
            throw e;
        }

        auditService.recordSuccess(request, response, prompt.version());
        String reviewJson = JsonUtil.toJson(result);
        markSuccess(campaignId, summary.getId(), reviewJson);
        CampaignAiReview row = findLatest(campaignId);
        if (row != null) {
            row.setModel(response.getModel());
            row.setPromptVersion(PromptVersionHolder.CAMPAIGN_REVIEW);
            reviewMapper.updateById(row);
        }
        return row;
    }

    /**
     * Mark SUCCESS: review JSON available, clear lock.
     */
    private void markSuccess(Long campaignId, Long summaryId, String reviewJson) {
        try {
            reviewMapper.update(null,
                    new LambdaUpdateWrapper<CampaignAiReview>()
                            .eq(CampaignAiReview::getCampaignId, campaignId)
                            .set(CampaignAiReview::getStatus, STATUS_SUCCESS)
                            .set(CampaignAiReview::getPerformanceSummaryId, summaryId)
                            .set(CampaignAiReview::getReviewJson, reviewJson)
                            .set(CampaignAiReview::getRetryable, false)
                            .set(CampaignAiReview::getLockedBy, null)
                            .set(CampaignAiReview::getLockedAt, null)
                            .set(CampaignAiReview::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("Failed to mark SUCCESS for campaignId={}: {}", campaignId, e.getMessage());
        }
    }

    /**
     * Mark RETRYABLE_FAILED: AI call failed (timeout/5xx), increment retry
     * count. If retryCount exceeds maxRetryCount, transition to PERMANENT_FAILED.
     * Sets a backoff nextRetryAt so the job doesn't immediately hammer the provider.
     */
    private void markRetryableFailed(Long campaignId, String failureCode,
                                      String errorMessage, Long summaryId) {
        try {
            CampaignAiReview row = findLatest(campaignId);
            int newRetryCount = (row != null && row.getRetryCount() != null)
                    ? row.getRetryCount() + 1 : 1;
            int maxRetry = properties.getReview().getMaxRetryCount();
            boolean permanent = newRetryCount > maxRetry;

            // Exponential backoff: 2^retryCount minutes (1, 2, 4, 8, ...)
            LocalDateTime nextRetryAt = permanent ? null
                    : LocalDateTime.now().plusMinutes((long) Math.pow(2, newRetryCount));

            reviewMapper.update(null,
                    new LambdaUpdateWrapper<CampaignAiReview>()
                            .eq(CampaignAiReview::getCampaignId, campaignId)
                            .set(CampaignAiReview::getStatus,
                                    permanent ? STATUS_PERMANENT_FAILED : STATUS_RETRYABLE_FAILED)
                            .set(CampaignAiReview::getFailureCode, failureCode)
                            .set(CampaignAiReview::getRetryable, !permanent)
                            .set(CampaignAiReview::getRetryCount, newRetryCount)
                            .set(CampaignAiReview::getNextRetryAt, nextRetryAt)
                            .set(errorMessage != null, CampaignAiReview::getErrorMessage,
                                    truncate(errorMessage, 480))
                            .set(summaryId != null, CampaignAiReview::getPerformanceSummaryId, summaryId)
                            .set(CampaignAiReview::getLockedBy, null)
                            .set(CampaignAiReview::getLockedAt, null)
                            .set(CampaignAiReview::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("Failed to mark RETRYABLE_FAILED for campaignId={}: {}",
                    campaignId, e.getMessage());
        }
    }

    /**
     * Mark SKIPPED_INSUFFICIENT_DATA: no send/audience data, not retryable.
     * The job will not re-scan this campaign.
     */
    private void markSkippedInsufficient(Long campaignId, Long summaryId) {
        try {
            reviewMapper.update(null,
                    new LambdaUpdateWrapper<CampaignAiReview>()
                            .eq(CampaignAiReview::getCampaignId, campaignId)
                            .set(CampaignAiReview::getStatus, STATUS_SKIPPED_INSUFFICIENT_DATA)
                            .set(CampaignAiReview::getFailureCode, "INSUFFICIENT_DATA")
                            .set(CampaignAiReview::getRetryable, false)
                            .set(CampaignAiReview::getErrorMessage, "insufficient data for review")
                            .set(summaryId != null, CampaignAiReview::getPerformanceSummaryId, summaryId)
                            .set(CampaignAiReview::getLockedBy, null)
                            .set(CampaignAiReview::getLockedAt, null)
                            .set(CampaignAiReview::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("Failed to mark SKIPPED for campaignId={}: {}", campaignId, e.getMessage());
        }
    }

    /**
     * Mark DATA_NOT_READY: audience &gt; 0 but {@code sentCount <= 0}, and the
     * campaign ended within the data-ready grace window. Send/conversion
     * pipelines may still be aggregating, so this is retryable — the job will
     * retry after {@code nextRetryAt} (= {@code campaign.endTime +
     * dataReadyDelayMinutes}, or now + delay when end time is unknown).
     */
    private void markDataNotReady(Long campaignId, Long summaryId, Campaign campaign) {
        try {
            int delayMinutes = properties.getReview().getDataReadyDelayMinutes();
            LocalDateTime nextRetryAt = campaign.getEndTime() == null
                    ? LocalDateTime.now().plusMinutes(delayMinutes)
                    : campaign.getEndTime().plusMinutes(delayMinutes);
            reviewMapper.update(null,
                    new LambdaUpdateWrapper<CampaignAiReview>()
                            .eq(CampaignAiReview::getCampaignId, campaignId)
                            .set(CampaignAiReview::getStatus, STATUS_DATA_NOT_READY)
                            .set(CampaignAiReview::getFailureCode, "DATA_NOT_READY")
                            .set(CampaignAiReview::getRetryable, true)
                            .set(CampaignAiReview::getNextRetryAt, nextRetryAt)
                            .set(CampaignAiReview::getErrorMessage, "campaign data not ready yet, will retry after grace")
                            .set(summaryId != null, CampaignAiReview::getPerformanceSummaryId, summaryId)
                            .set(CampaignAiReview::getLockedBy, null)
                            .set(CampaignAiReview::getLockedAt, null)
                            .set(CampaignAiReview::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("Failed to mark DATA_NOT_READY for campaignId={}: {}", campaignId, e.getMessage());
        }
    }

    /**
     * Mark PERMANENT_FAILED: non-retryable error (campaign not found, etc.).
     */
    private void markPermanentFailed(Long campaignId, String failureCode,
                                      String errorMessage, Long summaryId) {
        try {
            reviewMapper.update(null,
                    new LambdaUpdateWrapper<CampaignAiReview>()
                            .eq(CampaignAiReview::getCampaignId, campaignId)
                            .set(CampaignAiReview::getStatus, STATUS_PERMANENT_FAILED)
                            .set(CampaignAiReview::getFailureCode, failureCode)
                            .set(CampaignAiReview::getRetryable, false)
                            .set(errorMessage != null, CampaignAiReview::getErrorMessage,
                                    truncate(errorMessage, 480))
                            .set(summaryId != null, CampaignAiReview::getPerformanceSummaryId, summaryId)
                            .set(CampaignAiReview::getLockedBy, null)
                            .set(CampaignAiReview::getLockedAt, null)
                            .set(CampaignAiReview::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("Failed to mark PERMANENT_FAILED for campaignId={}: {}",
                    campaignId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildLlmInput(Campaign campaign, CampaignPerformanceSummary summary) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("campaignId", campaign.getId());
        input.put("objective", extractObjective(campaign));
        input.put("calculatedAt", summary.getCalculatedAt() == null
                ? LocalDateTime.now().toString() : summary.getCalculatedAt().toString());
        input.put("profileDataVersion", "v1");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("targetAudienceCount", summary.getTargetAudienceCount());
        metrics.put("sentCount", summary.getSentCount());
        metrics.put("deliveredCount", summary.getDeliveredCount());
        metrics.put("clickedCount", summary.getClickedCount());
        metrics.put("convertedCount", summary.getConvertedCount());
        metrics.put("unsubscribeCount", summary.getUnsubscribeCount());
        metrics.put("deliveryRate", summary.getDeliveryRate());
        metrics.put("clickRate", summary.getClickRate());
        metrics.put("conversionRate", summary.getConversionRate());
        metrics.put("unsubscribeRate", summary.getUnsubscribeRate());
        input.put("metrics", metrics);

        Map<String, Object> baseline = summary.getBaselineJson() == null
                ? Map.of()
                : JsonUtil.fromJson(summary.getBaselineJson(), Map.class);
        input.put("historicalBaseline", baseline);
        input.put("baselineDataVersion", "v1");

        input.put("contentVariants", summary.getVariantMetricsJson() == null
                ? java.util.List.of()
                : JsonUtil.fromJson(summary.getVariantMetricsJson(), java.util.List.class));
        return input;
    }

    private String extractObjective(Campaign campaign) {
        String desc = campaign.getDescription();
        if (desc != null && desc.startsWith("[OBJ:")) {
            int end = desc.indexOf(']');
            if (end > 5) return desc.substring(5, end);
        }
        return "UNKNOWN";
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private int safeInt(Integer i) { return i == null ? 0 : i; }

    /** Lock acquisition outcome. */
    private static final class LockResult {
        private final boolean acquired;
        private final String skipReason;

        private LockResult(boolean acquired, String skipReason) {
            this.acquired = acquired;
            this.skipReason = skipReason;
        }

        boolean isAcquired() { return acquired; }
        String skipReason() { return skipReason; }

        static LockResult acquired() { return new LockResult(true, null); }
        static LockResult skipped(String reason) { return new LockResult(false, reason); }
    }

    private static final class PromptVersionHolder {
        static final String CAMPAIGN_REVIEW = "campaign-review-v1";
    }
}
