package com.pulseflow.ai.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI-generated campaign review.
 *
 * <p>State machine (design §7.1.3):</p>
 * <pre>
 *   (absent) --insert--> PENDING --CAS--> PROCESSING --AI call--> SUCCESS / FAILED
 *                                                                    |
 *                                              (re-generate)          |
 *                                                  PENDING <-----------+
 * </pre>
 *
 * <p>UK on {@code campaign_id} → at most one row per campaign. The
 * {@link #status} field plus a conditional UPDATE form a compare-and-swap
 * lock that prevents two executors from calling the LLM concurrently for
 * the same campaign.</p>
 *
 * <p>Status values:</p>
 * <ul>
 *   <li>{@code PENDING} — row inserted, waiting for an executor to pick up</li>
 *   <li>{@code PROCESSING} — an executor has won the CAS lock and is calling AI</li>
 *   <li>{@code SUCCESS} — review JSON available</li>
 *   <li>{@code RETRYABLE_FAILED} — AI failed (timeout/5xx); job will retry up to maxRetryCount</li>
 *   <li>{@code SKIPPED_INSUFFICIENT_DATA} — no send/audience data; not retried (design §7.1.5)</li>
 *   <li>{@code PERMANENT_FAILED} — retry limit exceeded or non-retryable error</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("campaign_ai_review")
public class CampaignAiReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campaignId;

    private Long performanceSummaryId;

    /** ReviewResult JSON. Null while PENDING / PROCESSING. */
    private String reviewJson;

    private String model;

    private String promptVersion;

    /** PENDING / PROCESSING / SUCCESS / RETRYABLE_FAILED / SKIPPED_INSUFFICIENT_DATA / PERMANENT_FAILED. */
    private String status;

    private String errorMessage;

    /** Machine-readable failure reason (AI_TIMEOUT, INSUFFICIENT_DATA, etc.). */
    private String failureCode;

    /** Whether the next job run should retry this row. */
    private Boolean retryable;

    /** Number of AI call attempts so far. */
    private Integer retryCount;

    /** Earliest time the job may retry (backoff). */
    private LocalDateTime nextRetryAt;

    /** Executor/node id that won the PROCESSING CAS lock. */
    private String lockedBy;

    private LocalDateTime lockedAt;

    /** MyBatis-Plus optimistic lock version. */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
