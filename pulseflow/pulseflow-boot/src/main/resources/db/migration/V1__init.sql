-- ============================================================
-- PulseFlow V1: 初始建表 (15 张核心表)
-- ============================================================

-- 1. 用户基础信息
CREATE TABLE user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    nickname VARCHAR(64),
    avatar VARCHAR(256),
    mobile VARCHAR(20),
    email VARCHAR(128),
    status TINYINT DEFAULT 1 COMMENT '1=正常 0=禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基础信息';

-- 2. 行为事件归档
CREATE TABLE user_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    target_id BIGINT COMMENT '目标对象ID（商品/内容等）',
    event_time DATETIME(3) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    effective_event_time DATETIME(3) NOT NULL COMMENT '业务计算统一时间',
    clock_skew TINYINT DEFAULT 0 COMMENT '0=正常 1=时钟偏差',
    properties JSON COMMENT '扩展属性',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_user_time (user_id, effective_event_time),
    KEY idx_event_type (event_type, effective_event_time),
    KEY idx_effective_time (effective_event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行为事件归档';

-- 3. 小时指标桶
CREATE TABLE user_metric_hourly (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    metric_hour DATETIME NOT NULL COMMENT '指标小时（精确到小时）',
    event_type VARCHAR(32) NOT NULL,
    event_count INT DEFAULT 0,
    duration_sum BIGINT DEFAULT 0 COMMENT '时长累计(ms)',
    amount_sum DECIMAL(12,2) DEFAULT 0.00 COMMENT '金额累计',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_hour_type (user_id, metric_hour, event_type),
    KEY idx_metric_hour (metric_hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小时指标桶';

-- 4. 日指标桶
CREATE TABLE user_metric_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    metric_date DATE NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_count INT DEFAULT 0,
    duration_sum BIGINT DEFAULT 0,
    amount_sum DECIMAL(12,2) DEFAULT 0.00,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_date_type (user_id, metric_date, event_type),
    KEY idx_metric_date (metric_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日指标桶';

-- 5. 行为汇总（窗口指标结果）
CREATE TABLE user_behavior_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    metric_type VARCHAR(32) NOT NULL COMMENT 'search_1h/active_7d/spend_30d/fav_7d等',
    metric_value DECIMAL(12,2) DEFAULT 0.00,
    calculated_at DATETIME NOT NULL,
    window_start DATETIME,
    window_end DATETIME,
    UNIQUE KEY uk_user_metric_calc (user_id, metric_type, calculated_at),
    KEY idx_user_metric_type (user_id, metric_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行为汇总窗口指标';

-- 6. 用户标签
CREATE TABLE user_tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tag_name VARCHAR(64) NOT NULL COMMENT 'AI_PREF/HIGH_VALUE/CHURN_RISK/PRICE_SEN等',
    tag_value VARCHAR(128),
    calculated_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_tag_calc (user_id, tag_name, calculated_at),
    KEY idx_tag_name (tag_name, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户标签结果';

-- 7. 触达活动定义
CREATE TABLE campaign (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    trigger_type ENUM('EVENT','DELAYED','SCHEDULED') NOT NULL COMMENT '触发类型',
    cron_expression VARCHAR(64) COMMENT '定时 cron 表达式',
    event_types VARCHAR(256) COMMENT '关联事件类型（逗号分隔）',
    delay_seconds INT COMMENT '延迟触发秒数',
    channel VARCHAR(32) NOT NULL COMMENT '站内信/PUSH/EMAIL',
    message_template TEXT COMMENT '消息模板',
    user_daily_limit INT DEFAULT 3 COMMENT '用户日频控上限',
    campaign_weekly_limit INT DEFAULT 1 COMMENT '活动周频控上限',
    status ENUM('DRAFT','ACTIVE','PAUSED','CLOSED') DEFAULT 'DRAFT',
    start_time DATETIME,
    end_time DATETIME,
    next_trigger_at DATETIME COMMENT '下次触发时间',
    last_trigger_at DATETIME COMMENT '上次触发时间',
    version INT DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status_trigger (status, trigger_type, next_trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触达活动定义';

-- 8. 活动圈选规则
CREATE TABLE campaign_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    rule_name VARCHAR(64) NOT NULL,
    rule_type VARCHAR(32) NOT NULL COMMENT 'PROFILE/FREQUENCY/EVENT等',
    rule_config JSON NOT NULL COMMENT '规则配置JSON（条件运算符、阈值等）',
    priority INT DEFAULT 0 COMMENT '优先级（数字越小越先）',
    enabled TINYINT DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动圈选规则';

-- 9. 活动执行实例
CREATE TABLE campaign_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    scheduled_at DATETIME NOT NULL,
    status ENUM('PENDING','RUNNING','DONE','FAILED') DEFAULT 'PENDING',
    started_at DATETIME,
    finished_at DATETIME,
    retry_count INT DEFAULT 0,
    last_error VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campaign_schedule (campaign_id, scheduled_at),
    KEY idx_status (status, started_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动执行实例';

-- 10. 触达任务
CREATE TABLE delivery_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    dedup_key VARCHAR(128) NOT NULL COMMENT '业务去重键',
    trigger_event_id VARCHAR(64) COMMENT '触发事件ID',
    channel VARCHAR(32) NOT NULL,
    status ENUM('PENDING','PROCESSING','SENT','WAIT_RETRY','CANCELLED','FAILED') DEFAULT 'PENDING',
    dispatch_status ENUM('PENDING','PUBLISHED') DEFAULT 'PENDING',
    message_content TEXT COMMENT '消息内容',
    retry_count INT DEFAULT 0,
    next_retry_at DATETIME,
    processing_at DATETIME,
    published_at DATETIME,
    last_error VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dedup (dedup_key),
    KEY idx_dispatch (dispatch_status, created_at),
    KEY idx_processing (status, processing_at),
    KEY idx_status_retry (status, next_retry_at),
    KEY idx_campaign_user (campaign_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触达任务';

-- 11. 触达发送记录
CREATE TABLE delivery_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status ENUM('SENT','FAILED') DEFAULT 'SENT',
    sent_at DATETIME,
    error_msg VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_campaign_sent (campaign_id, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触达发送记录';

-- 12. 点击事件
CREATE TABLE click_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id BIGINT COMMENT '关联触达任务ID',
    click_time DATETIME(3) NOT NULL,
    click_source VARCHAR(64) COMMENT '来源标识',
    properties JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_click (user_id, click_time),
    KEY idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点击事件';

-- 13. 归因等待任务
CREATE TABLE attribution_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_event_id VARCHAR(64) NOT NULL COMMENT '目标事件ID',
    user_id BIGINT NOT NULL,
    target_event_type VARCHAR(32) NOT NULL,
    target_event_time DATETIME(3) NOT NULL,
    status ENUM('PENDING','MATCHED','EXPIRED') DEFAULT 'PENDING',
    grace_until DATETIME NOT NULL COMMENT '宽限窗口到期时间',
    matched_task_id BIGINT COMMENT '匹配到的触达任务ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_target_event (target_event_id),
    KEY idx_user_grace (user_id, grace_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归因等待任务';

-- 14. 归因结果记录
CREATE TABLE attribution_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    click_event_id BIGINT NOT NULL,
    target_event_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    campaign_id BIGINT,
    task_id BIGINT COMMENT '关联触达任务ID',
    attribution_model VARCHAR(32) DEFAULT 'CLICK_LAST_TOUCH',
    attribution_window_hours INT DEFAULT 24,
    credited_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_target_event_id (target_event_id),
    KEY idx_campaign_credited (campaign_id, credited_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归因结果记录';

-- 15. 数据补偿任务
CREATE TABLE data_compensation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL COMMENT '当前版本仅 EVENT_REPLAY',
    payload JSON NOT NULL,
    status ENUM('PENDING','PROCESSING','DONE','FAILED') DEFAULT 'PENDING',
    retry_count INT DEFAULT 0,
    max_retry INT DEFAULT 5,
    next_retry_at DATETIME,
    locked_at DATETIME,
    last_error VARCHAR(512),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_task (event_id, task_type),
    KEY idx_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据补偿任务';
