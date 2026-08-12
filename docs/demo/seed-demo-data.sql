-- ============================================================
-- PulseFlow 演示种子数据
-- 用途：为 AI Campaign Copilot 端到端演示准备稳定的人群画像
-- 演示场景：筛选 activeDays7d>=5 且 spend30d>500 且 daysSinceLastPurchase>=3 的用户
-- 使用：mysql -u root -p pulseflow < seed-demo-data.sql
-- 幂等：先 DELETE 再 INSERT，可重复执行
-- ============================================================

-- 清理演示用户的历史数据（幂等）
DELETE FROM user_behavior_summary WHERE user_id IN (1024, 1025, 1026, 1027, 1028);
DELETE FROM user_tag               WHERE user_id IN (1024, 1025, 1026, 1027, 1028);
DELETE FROM user_profile           WHERE user_id IN (1024, 1025, 1026, 1027, 1028);

-- ============================================================
-- 1. 用户基础信息（status=1 才会进入人群预估候选池）
-- ============================================================
INSERT INTO user_profile (user_id, nickname, status) VALUES
  (1024, '演示用户A-高消费未复购', 1),
  (1025, '演示用户B-高消费未复购', 1),
  (1026, '演示用户C-高消费未复购', 1),
  (1027, '对照用户D-活跃不足',     1),
  (1028, '对照用户E-消费不足',     1);

-- ============================================================
-- 2. 窗口指标（user_behavior_summary）
--    人群预估按 metric_type 取每用户最新 calculated_at 的 metric_value 比较
-- ============================================================
-- 演示用户 A/B/C：三条规则全部命中 → 预估人数 = 3
INSERT INTO user_behavior_summary (user_id, metric_type, metric_value, calculated_at, window_start, window_end) VALUES
  -- 用户 1024：活跃6天 / 30天消费880.50 / 距上次购买5天 → 命中
  (1024, 'active_7d',             6.00,   NOW(), DATE_SUB(NOW(), INTERVAL 7 DAY),  NOW()),
  (1024, 'spend_30d',             880.50, NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  (1024, 'daysSinceLastPurchase', 5.00,   NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  -- 用户 1025：活跃7天 / 30天消费1520.00 / 距上次购买9天 → 命中
  (1025, 'active_7d',             7.00,   NOW(), DATE_SUB(NOW(), INTERVAL 7 DAY),  NOW()),
  (1025, 'spend_30d',             1520.00,NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  (1025, 'daysSinceLastPurchase', 9.00,   NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  -- 用户 1026：活跃5天 / 30天消费699.00 / 距上次购买3天 → 命中（边界值）
  (1026, 'active_7d',             5.00,   NOW(), DATE_SUB(NOW(), INTERVAL 7 DAY),  NOW()),
  (1026, 'spend_30d',             699.00, NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  (1026, 'daysSinceLastPurchase', 3.00,   NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  -- 对照用户 1027：活跃2天（<5）→ 不命中
  (1027, 'active_7d',             2.00,   NOW(), DATE_SUB(NOW(), INTERVAL 7 DAY),  NOW()),
  (1027, 'spend_30d',             1200.00,NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  (1027, 'daysSinceLastPurchase', 4.00,   NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  -- 对照用户 1028：消费200（<=500）→ 不命中
  (1028, 'active_7d',             6.00,   NOW(), DATE_SUB(NOW(), INTERVAL 7 DAY),  NOW()),
  (1028, 'spend_30d',             200.00, NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW()),
  (1028, 'daysSinceLastPurchase', 4.00,   NOW(), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW());

-- ============================================================
-- 3. 用户标签（演示洞察用）
-- ============================================================
INSERT INTO user_tag (user_id, tag_name, tag_value, calculated_at) VALUES
  (1024, 'HIGH_VALUE',     '1', NOW()),
  (1024, 'PRICE_SENSITIVE','1', NOW()),
  (1025, 'HIGH_VALUE',     '1', NOW()),
  (1026, 'CHURN_RISK',     '1', NOW());

-- 验证：预估应返回 3（用户 1024/1025/1026 命中，1027/1028 被排除）
SELECT 'seeded users' AS info, COUNT(*) AS cnt FROM user_profile WHERE user_id IN (1024,1025,1026,1027,1028);
