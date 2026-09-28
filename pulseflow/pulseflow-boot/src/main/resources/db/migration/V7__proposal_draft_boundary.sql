DROP TABLE agent_campaign_draft_grant;

ALTER TABLE campaign_ai_draft
    ADD COLUMN investigation_id VARCHAR(36) NULL AFTER request_id,
    ADD KEY idx_ai_draft_investigation (investigation_id);
