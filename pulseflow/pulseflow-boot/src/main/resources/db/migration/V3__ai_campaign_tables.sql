-- ============================================================
-- PulseFlow V3: AI Campaign Copilot tables
--
-- Design §12. All four tables are additive; existing core tables
-- (campaign, campaign_rule, delivery_task, etc.) are NOT touched.
-- AI is an optional enhancement layer.
-- ============================================================

-- 1. AI Campaign 草稿
CREATE TABLE campaign_ai_draft (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    operator_id BIGINT NULL,
    source_text TEXT NOT NULL,
    schema_version INT NOT NULL,
    dsl_json JSON NOT NULL,
    validation_status VARCHAR(32) NOT NULL COMMENT 'GENERATED/NEEDS_CONFIRMATION/VALIDATED/INVALID/CONFIRMED/EXPIRED',
    validation_errors_json JSON NULL,
    warnings_json JSON NULL,
    estimated_audience_count BIGINT NULL,
    profile_data_version VARCHAR(64) NULL,
    confirmed_campaign_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,
    confirmed_at DATETIME NULL,
    UNIQUE KEY uk_ai_draft_request (request_id),
    KEY idx_ai_draft_operator_created (operator_id, created_at),
    KEY idx_ai_draft_status (validation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Campaign 草稿';

-- 2. AI 调用审计
CREATE TABLE ai_generation_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    operator_id BIGINT NULL,
    task_type VARCHAR(32) NOT NULL COMMENT 'PARSE_DSL/INSIGHT/CONTENT/REVIEW',
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    sanitized_input_json JSON NULL,
    structured_output_json JSON NULL,
    status VARCHAR(32) NOT NULL COMMENT 'SUCCESS/FAILED/INVALID',
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    latency_ms BIGINT NULL,
    draft_id BIGINT NULL,
    campaign_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_request_id (request_id),
    KEY idx_ai_task_created (task_type, created_at),
    KEY idx_ai_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用审计';

-- 3. Campaign 效果摘要（后端计算，AI 只读）
CREATE TABLE campaign_performance_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    target_audience_count BIGINT NOT NULL DEFAULT 0,
    sent_count BIGINT NOT NULL DEFAULT 0,
    delivered_count BIGINT NOT NULL DEFAULT 0,
    clicked_count BIGINT NOT NULL DEFAULT 0,
    converted_count BIGINT NOT NULL DEFAULT 0,
    unsubscribe_count BIGINT NOT NULL DEFAULT 0,
    delivery_rate DECIMAL(10,6) NULL,
    click_rate DECIMAL(10,6) NULL,
    conversion_rate DECIMAL(10,6) NULL,
    unsubscribe_rate DECIMAL(10,6) NULL,
    baseline_json JSON NULL,
    variant_metrics_json JSON NULL,
    calculated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campaign_performance (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Campaign 效果摘要';

-- 4. AI Campaign 复盘
CREATE TABLE campaign_ai_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    performance_summary_id BIGINT NOT NULL,
    review_json JSON NOT NULL,
    model VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'SUCCESS/FAILED',
    error_message VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campaign_ai_review (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Campaign 复盘';
