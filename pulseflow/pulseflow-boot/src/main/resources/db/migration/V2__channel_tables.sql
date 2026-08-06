-- ============================================================
-- PulseFlow V2: channel-specific idempotency tables
--
-- Design §4.3 requires per-channel business_key idempotency so that
-- "channel send succeeded but DB update failed" retries do not produce
-- duplicate messages. delivery_record.uk_task_id alone is not enough
-- because for EMAIL the external SMTP send has no DB-side guard.
-- ============================================================

-- 站内信发送记录 (business_key = delivery_task.id)
CREATE TABLE in_app_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_key BIGINT NOT NULL COMMENT '业务键 = delivery_task.id',
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    content TEXT,
    read_status TINYINT DEFAULT 0 COMMENT '0=未读 1=已读',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_business_key (business_key),
    KEY idx_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信发送记录';

-- 模拟 Push 发送记录 (business_key = delivery_task.id)
CREATE TABLE push_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_key BIGINT NOT NULL COMMENT '业务键 = delivery_task.id',
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    title VARCHAR(128),
    content TEXT,
    pushed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_business_key (business_key),
    KEY idx_user (user_id, pushed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟Push发送记录';
