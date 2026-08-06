-- V5: Review status split (retryable / skipped / permanent) + campaign ownership
-- Part of stage 7.2 final hardening.

-- 1. Campaign ownership: record who created each campaign so AI draft/review
--    endpoints can enforce resource-level access control (design §7.2.3).
ALTER TABLE campaign
    ADD COLUMN created_by BIGINT NULL COMMENT 'Operator who created this campaign (NULL for legacy rows)';

-- 2. Review status split: distinguish retryable AI failures from permanent
--    skips so XXL-JOB does not infinitely re-scan data-insufficient campaigns.
ALTER TABLE campaign_ai_review
    ADD COLUMN failure_code  VARCHAR(64) NULL     COMMENT 'Machine-readable failure reason (AI_TIMEOUT, INSUFFICIENT_DATA, etc.)',
    ADD COLUMN retryable     TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Whether the next job run should retry this row',
    ADD COLUMN retry_count   INT NOT NULL DEFAULT 0 COMMENT 'Number of AI call attempts so far',
    ADD COLUMN next_retry_at DATETIME NULL         COMMENT 'Earliest time the job may retry (backoff)';

-- 3. Migrate legacy FAILED rows: conservatively treat them as retryable so
--    the next job run picks them up. Once retry_count exceeds the limit they
--    will transition to PERMANENT_FAILED.
UPDATE campaign_ai_review
    SET status = 'RETRYABLE_FAILED',
        retryable = 1,
        failure_code = COALESCE(failure_code, 'LEGACY_FAILED')
    WHERE status = 'FAILED';

-- 4. Widen the scan index to cover the new retryable status + next_retry_at.
DROP INDEX idx_ai_review_status ON campaign_ai_review;
CREATE INDEX idx_ai_review_status_retry ON campaign_ai_review (status, next_retry_at, updated_at);
