-- Local/demo database only. Run after Flyway V10, using the application JVM's time zone.
-- Reuse a clean database for a fresh demo on another calendar day.
SET @today = CURRENT_DATE();
SET @yesterday = DATE_SUB(@today, INTERVAL 1 DAY);
SET @hour = TIMESTAMP(DATE_FORMAT(NOW(), '%Y-%m-%d %H:00:00'));

INSERT IGNORE INTO user_profile (user_id, nickname, status) VALUES
  (1024, '演示用户 A（活跃未购买）', 1),
  (1025, '演示用户 B（活跃已购买）', 1),
  (1026, '演示用户 C（活跃不足）', 1),
  (1027, '演示用户 D（账号禁用）', 0);

INSERT IGNORE INTO user_event
  (event_id, user_id, event_type, event_time, received_at, effective_event_time, properties)
VALUES
  ('pf-core-demo-01', 1024, 'CONTENT_VIEW', @yesterday, @yesterday, @yesterday, JSON_OBJECT('category','electronics')),
  ('pf-core-demo-02', 1024, 'CONTENT_VIEW', @hour, @hour, @hour, JSON_OBJECT('category','electronics')),
  ('pf-core-demo-03', 1025, 'CONTENT_VIEW', @yesterday, @yesterday, @yesterday, JSON_OBJECT()),
  ('pf-core-demo-04', 1025, 'CONTENT_VIEW', @hour, @hour, @hour, JSON_OBJECT()),
  ('pf-core-demo-05', 1025, 'ORDER_PAID', @hour, @hour, @hour, JSON_OBJECT('orderId','demo-paid','price',19.90)),
  ('pf-core-demo-06', 1026, 'CONTENT_VIEW', @hour, @hour, @hour, JSON_OBJECT()),
  ('pf-core-demo-07', 1027, 'CONTENT_VIEW', @yesterday, @yesterday, @yesterday, JSON_OBJECT()),
  ('pf-core-demo-08', 1027, 'CONTENT_VIEW', @hour, @hour, @hour, JSON_OBJECT());

INSERT IGNORE INTO user_metric_hourly (user_id, metric_hour, event_type, event_count, amount_sum)
SELECT user_id, effective_event_time, event_type, COUNT(*),
       SUM(COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(properties, '$.price')) AS DECIMAL(12,2)), 0))
FROM user_event WHERE event_id LIKE 'pf-core-demo-%'
GROUP BY user_id, effective_event_time, event_type;

INSERT IGNORE INTO campaign
    (id, name, trigger_type, event_types, delay_seconds, channel, message_template, status, next_trigger_at, created_by)
VALUES
    (97001, '商品浏览实时促销', 'EVENT', 'CONTENT_VIEW', NULL, 'IN_APP', '浏览专享优惠', 'ACTIVE', NULL, 1024),
    (97002, '加购未购买提醒', 'DELAYED', 'ADD_CART', 60, 'PUSH', '购物车商品仍可享优惠', 'ACTIVE', NULL, 1024),
    (97003, '近期活跃未购买召回', 'SCHEDULED', NULL, NULL, 'IN_APP', '近期活跃用户专享优惠', 'ACTIVE', NOW(), 1024);

INSERT INTO campaign_rule (campaign_id, rule_name, rule_type, rule_config)
SELECT 97001, 'demo-electronics', 'EVENT', JSON_OBJECT('propertyKey','category','propertyValue','electronics')
WHERE NOT EXISTS (SELECT 1 FROM campaign_rule WHERE campaign_id=97001 AND rule_name='demo-electronics');

-- Trigger dailyMetricJob and windowMetricJob before campaignSelectionJob. Only user 1024 matches.
-- Submit fresh events through POST /api/events to exercise Kafka, Redis and attribution.
