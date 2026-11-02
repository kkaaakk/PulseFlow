-- Keep legacy click IDs (and existing attribution references) intact. NULL
-- source_event_id explicitly means legacy/unverified: never infer trust from taskId.
ALTER TABLE click_event
    ADD COLUMN source_event_id VARCHAR(64) NULL COMMENT 'Validated source user_event.event_id',
    ADD UNIQUE KEY uk_click_source_event (source_event_id),
    ADD KEY idx_click_time_source (click_time, source_event_id);

ALTER TABLE user_event
    ADD COLUMN marketing_trusted TINYINT NOT NULL DEFAULT 0 COMMENT 'Server-authenticated ingestion provenance',
    ADD COLUMN marketing_click_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/VALID/REJECTED',
    ADD COLUMN marketing_click_reason VARCHAR(64) NULL COMMENT 'Safe reason code, never raw payload/credential',
    ADD KEY idx_pending_marketing_click (user_id, marketing_click_status, effective_event_time);

-- These access paths are shared by the authoritative aggregates and Last-Touch.
CREATE INDEX idx_click_task_user_time ON click_event (task_id, user_id, click_time);
CREATE INDEX idx_attribution_status_id ON attribution_task (status, id);

-- Do not backfill historical CLICKs: their public userId/taskId is not proof
-- of a genuine marketing association. See docs/marketing-click-pipeline.md.
