-- ============================================================
-- PulseFlow V4: AI Review state machine for concurrent job guard
--
-- Design §7.1.3: prevent duplicate AI calls when multiple XXL-JOB
-- executors scan the same finished campaign. Introduces a
-- PENDING → PROCESSING → SUCCESS / FAILED state machine on
-- campaign_ai_review, with a conditional UPDATE used as a
-- compare-and-swap lock.
--
-- Also relaxes NOT NULL constraints so a PENDING/PROCESSING row
-- (which has no reviewJson yet) can be inserted.
-- ============================================================

-- 1. Allow review_json / model / prompt_version / performance_summary_id
--    to be NULL until the AI call completes.
ALTER TABLE campaign_ai_review
    MODIFY COLUMN performance_summary_id BIGINT NULL,
    MODIFY COLUMN review_json JSON NULL,
    MODIFY COLUMN model VARCHAR(64) NULL,
    MODIFY COLUMN prompt_version VARCHAR(32) NULL;

-- 2. Extend status enum (string column; no DB-level constraint in v1).
--    New values: PENDING / PROCESSING / SUCCESS / FAILED.
--    A row is created in PENDING state by the job BEFORE calling the LLM,
--    transitions to PROCESSING via conditional UPDATE (the CAS lock),
--    then to SUCCESS or FAILED.
--    Old rows with status='SUCCESS'/'FAILED' remain valid.

-- 3. Lock-owner column: the executor that wins the CAS keeps its id here
--    so operators can see which node is processing.
ALTER TABLE campaign_ai_review
    ADD COLUMN locked_by VARCHAR(64) NULL COMMENT 'executor/node id holding PROCESSING lock',
    ADD COLUMN locked_at DATETIME NULL COMMENT 'when the PROCESSING lock was acquired',
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT 'optimistic lock version';

-- 4. Index to find PENDING/FAILED rows that need (re)processing.
CREATE INDEX idx_ai_review_status ON campaign_ai_review (status, updated_at);
