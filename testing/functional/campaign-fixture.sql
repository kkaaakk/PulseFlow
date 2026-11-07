-- PulseFlow Phase 1 campaign/frequency/attribution fixture.
-- TEST ONLY: run against pulseflow_test after Flyway V1~V9.
-- The wrapper refuses non-test targets; do not paste this into production.

INSERT INTO campaign (
    id, name, description, trigger_type, event_types, channel, message_template,
    user_daily_limit, campaign_weekly_limit, status, version, created_by
)
VALUES (
    9202, 'PF_TEST_FREQUENCY_V1', 'Phase 1 frequency fixture', 'EVENT', 'CONTENT_VIEW',
    'IN_APP', 'Phase 1 frequency fixture message', 2, 2, 'ACTIVE', 0, 9000001
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name), description = VALUES(description), trigger_type = VALUES(trigger_type),
    event_types = VALUES(event_types), channel = VALUES(channel), message_template = VALUES(message_template),
    user_daily_limit = VALUES(user_daily_limit), campaign_weekly_limit = VALUES(campaign_weekly_limit),
    status = VALUES(status), created_by = VALUES(created_by);

UPDATE campaign_rule
SET rule_type = 'EVENT',
    rule_config = '{"propertyKey":"scenario","propertyValue":"frequency-v1"}',
    priority = 0,
    enabled = 1
WHERE campaign_id = 9202 AND rule_name = 'phase1-scenario';

INSERT INTO campaign_rule (
    campaign_id, rule_name, rule_type, rule_config, priority, enabled
)
SELECT 9202, 'phase1-scenario', 'EVENT',
       '{"propertyKey":"scenario","propertyValue":"frequency-v1"}', 0, 1
WHERE NOT EXISTS (
    SELECT 1 FROM campaign_rule
    WHERE campaign_id = 9202 AND rule_name = 'phase1-scenario'
);

INSERT INTO campaign (
    id, name, description, trigger_type, event_types, channel, message_template,
    user_daily_limit, campaign_weekly_limit, status, version, created_by
)
VALUES (
    9203, 'PF_TEST_ATTRIBUTION_V1', 'Phase 1 attribution fixture', 'EVENT', 'ORDER_PAID',
    'IN_APP', 'Phase 1 attribution fixture message', 3, 3, 'ACTIVE', 0, 9000001
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name), description = VALUES(description), trigger_type = VALUES(trigger_type),
    event_types = VALUES(event_types), channel = VALUES(channel), message_template = VALUES(message_template),
    status = VALUES(status), created_by = VALUES(created_by);

-- Legacy CLI aggregate fixture seeds a task result. Full unseeded send/click acceptance is in JUnit.
-- Click facts must come from HTTP
-- -> Kafka with PULSEFLOW_EVENTS_TRUSTED_SOURCE_TOKEN configured on both the
-- test backend and replay process. Never pre-insert click_event for acceptance.
INSERT INTO delivery_task (
    id, campaign_id, user_id, dedup_key, channel, status, dispatch_status,
    message_content, retry_count
)
VALUES (
    9203, 9203, 7000001, 'PF_TEST_ATTRIBUTION_TASK_9203', 'IN_APP', 'SENT', 'PUBLISHED',
    'Phase 1 attribution fixture message', 0
)
ON DUPLICATE KEY UPDATE status = VALUES(status), dispatch_status = VALUES(dispatch_status);

-- Legacy CLI aggregate fixture only. Full unseeded delivery -> click acceptance is
-- MarketingClickPipelineIT.decisionSendClickPurchaseSummaryAndAgentCompleteTheEntireRealPipeline.
UPDATE delivery_task SET sent_at=COALESCE(sent_at,NOW()-INTERVAL 2 MINUTE),
    first_send_attempt_at=COALESCE(first_send_attempt_at,NOW()-INTERVAL 2 MINUTE) WHERE id=9203;

SELECT 'campaign fixture prepared' AS status,
       (SELECT id FROM campaign WHERE name = 'PF_TEST_FREQUENCY_V1') AS frequency_campaign_id,
       (SELECT id FROM campaign WHERE name = 'PF_TEST_ATTRIBUTION_V1') AS attribution_campaign_id;
