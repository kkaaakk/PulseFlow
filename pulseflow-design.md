# PulseFlow — 实时用户行为决策引擎 设计方案

> **版本**: 2.9 | **日期**: 2026-07-31 | **状态**: 开发基线就绪

---

## 一、项目定位

### 一句话描述

面向内容社区与电商增长场景的事件驱动实时行为决策引擎 — 采集用户行为，实时更新用户状态，按规则自动决策并执行动作，追踪转化效果形成闭环。

### 不是什么东西

- 不是运营后台管理工具（虽然包含必要的活动配置）
- 不是群发消息系统（核心是决策，触达只是动作之一）
- 不是用户管理系统（用户基础信息只用最少字段）

### 核心闭环

```
用户行为发生 → 实时状态更新 → 规则决策命中 → 自动执行动作 → 后续行为反馈 → 转化归因
```

### 项目名称

**PulseFlow** — 取自"用户行为脉搏"，强调实时性和流式处理特性。

---

## 二、整体架构

### 2.1 事件处理管道

```
┌──────────┐     ┌──────────┐     ┌──────────────────────────────┐
│ 行为接入  │────▶│  Kafka   │────▶│        消费分发               │
│ REST API │     │ 事件管道  │     │                              │
└──────────┘     └──────────┘     └──┬──────────┬──────────┬─────┘
                                     │          │          │
                                  Redis      MySQL        ES
                               实时状态·频控  业务数据  行为索引(Stage2)
                               延迟任务·缓存  补偿任务
```

### 2.2 异构数据架构

| 组件 | 角色 | 存储内容 | 可靠性要求 |
|------|------|----------|-----------|
| **Kafka** | 事件管道，削峰解耦 | 原始事件流（限时保留） | — |
| **Redis** | 实时状态与缓存 | 实时指标、用户状态、频控计数、延迟任务ZSET、归因宽限ZSET | 可重建 |
| **MySQL** | 核心业务事实 + 补偿队列 | 事件归档、用户、活动配置、触达任务、归因记录、小时指标桶、补偿任务、活动执行实例 | 不可丢 |
| **Elasticsearch** | 行为检索(Stage 2) | 行为明细索引、聚合分析 | 可重建 |

**一致性原则**：

- MySQL 是唯一事实来源。事件写入和小时指标桶更新放在**同一个本地事务**中。补偿任务也写入 MySQL。
- Redis 可通过 Kafka 事件或 MySQL 数据重建，不强求与 MySQL 实时一致。更新失败写入 MySQL 补偿任务，由 XXL-JOB 异步重放。
- ES 通过补偿任务重索引，允许短暂延迟。
- Kafka 消费者必须支持重复执行（幂等消费）。

### 2.3 部署模式

第一版按**模块化单体**部署，模块间通过接口调用而非 RPC。后续可按模块拆分为微服务（CDP 框架天然支持一套代码双部署）。

### 2.4 Kafka 分区策略

**生产者必须以 userId 为 Key 投递消息**：

```java
kafkaTemplate.send("pulseflow.raw.events",
    String.valueOf(event.getUserId()),  // Key: userId，保证同一用户进入同一分区
    eventJson);
```

原因：同一用户的 `ADD_CART → REMOVE_CART → ORDER_PAID` 等关联事件如果在不同分区被不同消费者乱序处理，会导致购物车状态和实时决策错误。

这个约束写入代码规范，消费者代码依赖此假设。

**即使按用户分区，跨来源迟到事件仍然可能发生**，通过 `eventTime` 校验和归因宽限窗口处理。

---

## 三、五条核心链路

### 链路 1：行为接入与幂等消费

#### 1.1 完整处理流程

```
POST /api/events
  → 校验 eventId 格式、必填字段、eventTime 偏差（±5min 内）
  → 以 userId 为 Key 投递 Kafka
  → 返回 202 Accepted（仅在 Kafka broker 确认写入后，而非 send() 后立即返回）

Kafka Consumer 接收（单分区内保证同一用户事件顺序）:

  第一阶段 — MySQL 事务:
    BEGIN
      INSERT INTO user_event (UNIQUE KEY uk_event_id 兜底幂等)
      INSERT INTO user_metric_hourly ... ON DUPLICATE KEY UPDATE
    COMMIT
    若 DuplicateKeyException:
      → **不直接 ACK**（第一次处理可能在 MySQL 成功后、Redis 更新前宕机）
      → 仅跳过 user_event 和指标桶写入
      → **从 MySQL 查询该 eventId 的原始事件记录**作为标准事件
      → 使用数据库中已保存的事件继续执行第二阶段和第三阶段

  第二阶段 — Redis Lua 原子更新:
    EVAL lua_upsert_realtime_metrics.lua
      → 检查 event:processed:{eventId} 是否存在
      → 不存在 → HINCRBY / HSET 实时指标 + SET 处理标记(EX 604800)  -- 7天，≥ Kafka 保留期
      → 存在 → 跳过（幂等，重复执行安全）

  第三阶段 — 即时决策评估:
    DecisionEngine 加载关联活动 → 规则评估 → 快速预过滤 → 创建触达任务

  全部阶段成功:
    → ACK Kafka

  第二阶段或第三阶段失败:
    → INSERT INTO data_compensation_task (
        event_id, task_type='EVENT_REPLAY', payload, status='PENDING'
      ) ON DUPLICATE KEY UPDATE
          status = 'PENDING', retry_count = 0,
          next_retry_at = NOW(), locked_at = NULL,
          last_error = VALUES(last_error)
    → 补偿任务写入成功 → ACK Kafka

  第一阶段 MySQL 事务失败:
    → 不 ACK，抛出异常，由 Kafka 消费者重投

  补偿任务写入也失败:
    → 不 ACK，由 Kafka 消费者重投
```

**ACK 规则**：只有事实数据已落 MySQL（第一阶段成功），或者失败步骤已成功写入补偿任务时才能 ACK。任何其他情况一律不 ACK，交给 Kafka 重投。DuplicateKeyException 在第一阶段说明数据已存在，等同于第一阶段成功。

**DuplicateKeyException 的正确语义**：仅表示该事件已在 MySQL 落盘一次，不代表上次处理的 Redis 和决策阶段也完成了。重复消费时从 DB 读取标准事件继续执行——MySQL 始终是唯一事实来源，不依赖 Kafka 重放消息的字段内容。

#### 1.2 补偿任务恢复

两个阶段的需求（重建 Redis + 重试决策）合并为**单一补偿任务类型**，避免两条记录之间没有依赖字段导致顺序错乱：

```
CompensationJob 每 30 秒:
  1. 恢复卡死的 PROCESSING 任务:
     UPDATE data_compensation_task
     SET status = 'PENDING',
         retry_count = retry_count + 1,
         next_retry_at = NOW()
     WHERE status = 'PROCESSING'
       AND locked_at < NOW() - INTERVAL 5 MINUTE
       AND retry_count < max_retry

  2. 事务领取新任务（避免 MySQL 1093 自引用限制 + 多实例并发安全）:
     BEGIN;
       SELECT id FROM data_compensation_task
       WHERE status = 'PENDING' AND next_retry_at <= NOW()
       ORDER BY id LIMIT 1
       FOR UPDATE SKIP LOCKED;

       UPDATE data_compensation_task
       SET status = 'PROCESSING', locked_at = NOW()
       WHERE id = ?;
     COMMIT;

  3. 固定恢复流程:
     task_type = EVENT_REPLAY:
       ① 重放 Redis Lua 更新（幂等）
       ② 重新加载活动规则 → 决策评估
       ③ delivery_task.dedup_key UK 阻止任何重复触达
       ④ status = DONE

     执行失败:
       → retry_count + 1, locked_at = NULL
       → 未超 max_retry: status = PENDING, next_retry_at = {退避时间}
       → 已超 max_retry: status = FAILED
```

#### 1.3 事件模型

```json
{
  "eventId": "evt_20260731_10001",
  "userId": 1024,
  "eventType": "ADD_CART",
  "targetId": 8866,
  "eventTime": "2026-07-31T09:30:00",
  "properties": {
    "category": "AI",
    "price": 29.9,
    "cartItemId": "ci_501"
  }
}
```

#### 1.4 事件类型

`LOGIN | CONTENT_VIEW | SEARCH | LIKE | FAVORITE | ADD_CART | REMOVE_CART | ORDER_CREATE | ORDER_PAID | SHARE | CLICK`

#### 1.5 客户端时间校验

```
eventTime 校验规则:
  1. |eventTime - receivedAt| <= 5min → clock_skew = false
     → effective_event_time = eventTime
  2. 偏差 > 5min → clock_skew = true
     → effective_event_time = receivedAt
     → 记录告警
```

三个时间字段保留，新增 `effective_event_time` 作为统一的业务计算基准：
- `event_time`：客户端发生时间（保存，用于审计追溯）
- `received_at`：服务端接收时间
- `effective_event_time`：实际业务时间（实时指标、小时桶、归因匹配均使用此字段）

> `effective_event_time` 确保恶意或错误的未来/过去时间不会污染日期 Key、时间桶区间和归因窗口。

---

### 链路 2：三级用户画像计算

```
┌────────────────────────────────────────────────────┐
│                  用户画像 (UserProfile)              │
│                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │ 实时指标      │  │ 窗口指标      │  │ 长期标签   │ │
│  │ Redis Hash   │  │ MySQL聚合    │  │ MySQL+Cache│ │
│  │              │  │              │  │            │ │
│  │ lastLoginAt  │  │ search1h     │  │ AI_PREF    │ │
│  │ todayViews   │  │ active7d     │  │ HIGH_VALUE │ │
│  │ cartItems    │  │ spend30d     │  │ CHURN_RISK │ │
│  │              │  │ fav7d        │  │ PRICE_SEN  │ │
│  └──────┬───────┘  └──────┬───────┘  └─────┬─────┘ │
│         │                 │                 │       │
│    事件消费时更新      XXL-JOB 每小时      XXL-JOB 每日 │
│    (Lua原子操作)     (读时间桶聚合)    (策略模式规则)  │
└────────────────────────────────────────────────────┘
```

#### 2.1 实时指标（Redis）

**Key 设计**（分离长期状态、当日计数和购物车）：
```
user:rt:{userId}                       长期实时状态 (无 TTL)
  ├── last_login_at
  └── last_active_at

user:daily:{userId}:{yyyyMMdd}         当日计数 (TTL 48h)
  ├── views
  └── search_count

user:cart:{userId}                     购物车 (HASH, 无 TTL)
  cartItemId → JSON (商品信息)

注：Redis HASH 字段值只能是字符串，不能嵌套 HASH，因此购物车拆为独立 Key。
```

**更新方式**（Lua 原子化，根据事件类型操作对应 Key）：

```
lua_upsert_realtime_metrics.lua:
  1. EXISTS event:processed:{eventId} → 跳过，return
  2. 根据 eventType:
     LOGIN       → HSET user:rt  last_login_at {effectiveEventTime}
     CONTENT_VIEW → HINCRBY user:daily views 1
     SEARCH      → HINCRBY user:daily search_count 1
     ADD_CART    → HSET user:cart {cartItemId} {json}
     REMOVE_CART → HDEL user:cart {cartItemId}
     ORDER_PAID  → HDEL user:cart {cartItemId}
  3. HSET user:rt last_active_at {effectiveEventTime}
  4. SET event:processed:{eventId} 1 EX 604800  -- 7天，≥ Kafka 保留期
  5. return ok
```

#### 2.2 窗口指标（基于时间桶聚合）

**小时指标桶** `user_metric_hourly`（与 user_event 同一事务写入）：

```sql
INSERT INTO user_metric_hourly (user_id, metric_hour, event_type, event_count, duration_sum, amount_sum)
VALUES (?, ?, ?, 1, ?, ?)
ON DUPLICATE KEY UPDATE
    event_count = event_count + 1,
    duration_sum = duration_sum + VALUES(duration_sum),
    amount_sum = amount_sum + VALUES(amount_sum);
```

**日指标桶** `user_metric_daily`：

XXL-JOB 每小时重算昨天完整 24 小时 + 今天已封闭小时（避免部分覆盖）：

```sql
INSERT INTO user_metric_daily (user_id, metric_date, event_type, event_count, duration_sum, amount_sum)
SELECT user_id, DATE(metric_hour), event_type,
       SUM(event_count), SUM(duration_sum), SUM(amount_sum)
FROM user_metric_hourly
WHERE metric_hour >= DATE_SUB(CURDATE(), INTERVAL 1 DAY)   -- 昨天 00:00 起
  AND metric_hour <  DATE_FORMAT(NOW(), '%Y-%m-%d %H:00:00')  -- 已封闭小时
GROUP BY DATE(metric_hour), user_id, event_type
ON DUPLICATE KEY UPDATE
    event_count = VALUES(event_count),
    duration_sum = VALUES(duration_sum),
    amount_sum = VALUES(amount_sum);
```

> 聚合范围：昨天 00:00 至当前已封闭小时。每次覆盖昨天完整 24h + 今天已封闭小时，不会因窗口偏移导致历史数据被部分覆盖。

XXL-JOB 每小时聚合窗口指标（读日桶+当前小时桶）：

```
近1小时: 读当前小时桶
近7天:   读最近 7 个日桶
近30天:  读最近 30 个日桶
```

#### 2.3 长期标签（策略模式）

8 种标签规则，XXL-JOB 每日凌晨重算。

---

### 链路 3：三种触发模式与决策执行

#### 3.1 事件触发（即时决策）

```
Kafka 消费事件（在链路1的 Redis 更新之后）
  → 加载该事件类型关联的活跃活动
  → 规则条件评估（读取 Redis 实时指标 + MySQL 窗口指标/标签缓存）
  → 命中 → 快速预过滤（免打扰、已退订、已转化）
  → 通过 → 创建触达任务（含 dedup_key）

若 Redis 更新失败:
  → 写入 EVENT_REPLAY 补偿任务
  → 由 CompensationJob 在 Redis 状态恢复 + 决策重试后创建触达任务
  → delivery_task.dedup_key UK 防止重复触达
```

> 创建任务阶段不调用计数型频控 Lua。频控只在真正发送前执行一次（见链路 4.2）。

#### 3.2 延迟触发（Redis ZSET + Lua 原子领取）

**数据结构**：
- `delay:pending:{taskType}` — 待执行 ZSET，score = 到期时间戳
- `delay:processing:{taskType}` — 处理中 ZSET，score = 领取时间戳

```
独立线程每秒轮询 → Lua 领取(pending→processing)
  → MySQL 检查条件
  → 条件满足 → 创建触达任务 → ZREM processing
  → 条件不再满足 → CANCELLED → ZREM processing

恢复 Job（XXL-JOB 每 5 分钟）:
  → 扫描 processing 超时任务 → 放回 pending（最多 3 次）
  → 超限 → 写入 dead_letter 记录
```

#### 3.3 定时触发（统一扫描 + 执行实例表）

**核心原则**：不为每个活动动态创建 XXL-JOB 任务。使用单一 `CampaignSelectionJob` 统一扫描 + `campaign_execution` 实例表防止执行中断。

**campaign_execution 表**：

```sql
CREATE TABLE campaign_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    scheduled_at DATETIME NOT NULL,
    status ENUM('PENDING','RUNNING','DONE','FAILED') DEFAULT 'PENDING',
    started_at DATETIME,
    finished_at DATETIME,
    retry_count INT DEFAULT 0,
    last_error VARCHAR(512),
    UNIQUE KEY uk_campaign_schedule (campaign_id, scheduled_at),
    KEY idx_status (status, started_at)
) ENGINE=InnoDB;
```

**完整流程**（两阶段）：

```
CampaignSelectionJob 每分钟执行:

  -- 步骤0: 恢复卡死的 RUNNING 实例
  UPDATE campaign_execution SET status='PENDING', retry_count=retry_count+1
  WHERE status='RUNNING' AND started_at < NOW() - INTERVAL 10 MINUTE
    AND retry_count < 3;

  -- 超过最大重试的 RUNNING 标记为 FAILED
  UPDATE campaign_execution SET status='FAILED'
  WHERE status='RUNNING' AND started_at < NOW() - INTERVAL 10 MINUTE
    AND retry_count >= 3;

  -- ========== 阶段A: 为新到期活动创建执行实例 ==========
  -- 扫描 next_trigger_at <= NOW() 的活跃活动
  FOR each campaign WHERE status='ACTIVE' AND trigger_type='SCHEDULED'
                    AND next_trigger_at <= NOW():
    BEGIN
      -- 乐观锁推进 next_trigger_at
      UPDATE campaign
      SET next_trigger_at = {基于 cron_expression 计算的下次时间},
          last_trigger_at = NOW(),
          version = version + 1
      WHERE id = ? AND version = ?;

      -- 检查影响行数，非 1 则 ROLLBACK

      -- 创建执行实例
      INSERT INTO campaign_execution (campaign_id, scheduled_at, status)
      VALUES (?, {本次原 next_trigger_at}, 'PENDING');
    COMMIT

  -- ========== 阶段B: 执行所有 PENDING 实例 ==========
  -- 新创建的 PENDING 和恢复的 PENDING 都走此阶段
  SELECT ce.* FROM campaign_execution ce
  JOIN campaign c ON c.id = ce.campaign_id
  WHERE ce.status = 'PENDING' AND c.status = 'ACTIVE'
  ORDER BY ce.scheduled_at LIMIT 10;

  FOR each execution:
    -- 条件更新领取
    UPDATE campaign_execution SET status='RUNNING', started_at=NOW()
    WHERE id = ? AND status = 'PENDING';

    -- 只有影响行数为 1 的节点继续
    -- 执行人群圈选 + 创建触达任务(dedup_key = {executionId}:{userId})
    -- 标记 DONE
    UPDATE campaign_execution SET status='DONE', finished_at=NOW()
```
```

**关键设计**：CampaignSelectionJob 统一负责新到期活动的执行和恢复后 PENDING 实例的执行。恢复逻辑已内置在步骤 0，不另设独立 RecoveryJob。

**XXL-JOB 任务总览**（9 个）：

| Job | 频率 | 功能 |
|-----|------|------|
| WindowMetricJob | 每小时 | 聚合小时桶→窗口指标 |
| DailyMetricJob | 每小时 | 小时桶→日桶全量覆盖 |
| TagRecalcJob | 每日 02:00 | 策略模式重算长期标签 |
| CampaignSelectionJob | 每 1 分钟 | 统一扫描 + 乐观锁推进 |
| RetryCompensationJob | 每 1 分钟 | 扫描 WAIT_RETRY 任务重新置为 PENDING，超限改 FAILED |
| CompensationJob | 每 30 秒 | 扫描 data_compensation_task 重建 Redis + 重试决策 |
| DelayTaskRecoveryJob | 每 5 分钟 | 恢复超时的延迟任务 |
| DataCleanupJob | 每日 03:00 | 清理过期事件、历史日志 |
| DispatchRetryJob | 每 30 秒 | 补偿 Kafka 投递失败的触达任务 |

---

### 链路 4：触达执行与频控

#### 4.1 触达任务创建与去重

```
决策引擎生成 dedup_key:
  事件触发:   {campaignId}:{userId}:{eventId}
  延迟触发:   {campaignId}:{userId}:{cartItemId}:{addCartEventId}
  定时圈选:   {campaignExecutionId}:{userId}

INSERT INTO delivery_task (..., dedup_key, trigger_event_id)
  → UNIQUE KEY uk_dedup (dedup_key) 保证不重复
```

定时圈选改用 `{campaignExecutionId}:{userId}` 而非日期，解决同一天执行两次被误判重复的问题。

#### 4.2 四级频控（Lua 原子检查+占用）

频控的判超限和占位必须在**同一个 Lua 脚本**中完成，防止两个实例同时通过检查。

**计数语义**：发送前 Lua 原子判断并占用额度，一次触达任务只消耗一次额度——内部重试通过 `freq:reserved:{taskId}` 标记跳过重复计数，避免了故障时不断重试挤占用户频控额度的问题。

```lua
-- KEYS[1] = freq:user:{userId}:{date}
-- KEYS[2] = freq:campaign:{campaignId}:{userId}
-- KEYS[3] = freq:reserved:{taskId}
-- ARGV[1] = 用户日上限
-- ARGV[2] = 活动周上限
-- ARGV[3] = 活动频控 TTL

-- 重试任务已占用过额度，直接放行
if redis.call('EXISTS', KEYS[3]) == 1 then
    return {1, 'RETRY_OK'}
end

local userCount = tonumber(redis.call('GET', KEYS[1]) or 0)
local campaignCount = tonumber(redis.call('GET', KEYS[2]) or 0)

if userCount >= tonumber(ARGV[1]) then
    return {0, 'USER_LIMIT'}
end
if campaignCount >= tonumber(ARGV[2]) then
    return {0, 'CAMPAIGN_LIMIT'}
end

redis.call('INCR', KEYS[1])
redis.call('INCR', KEYS[2])
redis.call('SET', KEYS[3], '1', 'EX', 86400)  -- 24h，覆盖最大重试周期
redis.call('EXPIRE', KEYS[1], 86400)
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

return {1, 'OK'}
```

**执行时机**：仅在真正发送前调用一次，不在创建任务时预占。创建任务阶段只做免打扰时段和已转化过滤的非原子快速预判。

#### 4.3 触达执行与渠道幂等

```
触达链路完整流程:

  1. INSERT delivery_task (status=PENDING, dispatch_status=PENDING)
  
  2. 投递 Kafka (topic: delivery)
     → 成功: UPDATE dispatch_status='PUBLISHED', published_at=NOW()
     → 失败: 任务留在 DB，dispatch_status 保持 PENDING

  3. Kafka Consumer 消费:
     → 条件更新领取: 
       UPDATE task SET status='PROCESSING', processing_at=NOW()
       WHERE status='PENDING'
     → 仅行数为 1 的实例继续

  4. 频控检查 (Lua 原子)
     → 先查 freq:reserved:{taskId} 是否存在:
       存在 → 跳过计数（本次是重试），直接允许
     → 不存在 → 判断额度 + INCR + SET reserved 标记(EX 86400)
     → 未通过 → task status='CANCELLED'

  5. 通过 biz-message 策略模式发送，taskId 作为渠道业务键:
     
     站内信:    INSERT INTO in_app_message (business_key=taskId, ...)
                → UNIQUE KEY uk_business_key 保证不重复
                → INSERT INTO delivery_record (UK task_id)

     模拟 Push: INSERT INTO push_record (business_key=taskId, ...)
                → UNIQUE KEY uk_business_key 保证不重复
                → INSERT INTO delivery_record (UK task_id)

     邮件:      外部 SMTP 无法绝对保证幂等
                → 先 INSERT delivery_record (UK task_id)，成功后再发送邮件
                → 发送成功 UPDATE delivery_record status='SENT'
                → 发送失败 UPDATE delivery_record status='FAILED'
                → 重试时不重复 INSERT delivery_record（UK 已存在，更新状态即可）

  6. UPDATE task status='SENT'

  渠道发送失败:
     → delivery_record.status = 'FAILED', error_msg = {错误详情}
     → delivery_task.status = 'WAIT_RETRY'
     → delivery_task.next_retry_at = NOW() + {退避时间}
     → delivery_task.last_error = {错误详情}
     → retry_count + 1（retry_count 仅在发送失败或 PROCESSING 超时时增加）


DispatchRetryJob 每 30 秒:
  SELECT * FROM delivery_task
  WHERE dispatch_status = 'PENDING'
    AND created_at < NOW() - INTERVAL 30 SECOND
  ORDER BY id LIMIT 100

  → 重新投递 Kafka
  → 成功 → dispatch_status = 'PUBLISHED'

ProcessingRecoveryJob（在 RetryCompensationJob 中合并）:
  -- 将到期的 WAIT_RETRY 任务重置，重新走 Outbox 投递链路
  UPDATE delivery_task
  SET status = 'PENDING',
      dispatch_status = 'PENDING',
      published_at = NULL,
      processing_at = NULL,
      next_retry_at = NULL
  WHERE status = 'WAIT_RETRY'
    AND next_retry_at <= NOW()
    AND retry_count < max_retry;
  -- 重置后由 DispatchRetryJob 重新投递 Kafka

  -- 恢复卡在 PROCESSING 的触达任务（消费后进程宕机）→ 此步增加 retry_count
  UPDATE delivery_task
  SET status = 'WAIT_RETRY', retry_count = retry_count + 1
  WHERE status = 'PROCESSING'
    AND processing_at < NOW() - INTERVAL 5 MINUTE
    AND retry_count < max_retry;
  -- 站内信和模拟 Push 已有 business_key=taskId，重试不会重复发送
  -- 重试时命中 business_key UK → 视为发送成功，补写 delivery_record + 更新 SENT

  -- 终态：超过最大重试次数的任务
  UPDATE delivery_task SET status = 'FAILED'
  WHERE status IN ('PROCESSING', 'WAIT_RETRY')
    AND retry_count >= max_retry;

  UPDATE data_compensation_task SET status = 'FAILED'
  WHERE status = 'PROCESSING'
    AND retry_count >= max_retry;
```

---

### 链路 5：转化归因

#### 5.1 归因模型

**CLICK_LAST_TOUCH**（点击后 Last-Touch 归因）：

```
触达发送 → delivery_record
用户点击 → click_event
目标事件到达 → INSERT attribution_task + ZADD delay:attribution

宽限窗口到期（5min）后执行归因匹配:
  1. 查询归因窗口内(24h)该用户的有效 click_event
  2. 过滤: click_time > sent_at AND click_time < target_event_time
  3. 选最近一条 click（LAST-TOUCH）
  4. INSERT INTO attribution_record (UK uk_target_event_id)
  5. DuplicateKeyException → 已归因，跳过
```

#### 5.2 防错机制

| 场景 | 处理方式 |
|------|---------|
| 一单多活动 | `UNIQUE KEY uk_target_event_id` 保证一个目标事件只归因一次 |
| 点击迟到 | 宽限窗口 5min 等待迟到 click_event |
| 事件乱序 | `eventTime` 业务判断 + `receivedAt` 降级（clock_skew 时） |
| 客户端时间伪造 | ±5min 校验，超限标记 clock_skew，归因降级 |

---

## 四、数据库设计

### 4.1 表结构总览（14 张核心表）

```
用户与行为:
  user_profile             用户基础信息
  user_event               行为事件归档（原始数据）

指标与标签:
  user_metric_hourly       小时指标桶
  user_metric_daily        日指标桶
  user_behavior_summary    行为汇总（窗口指标结果）
  user_tag                 用户标签结果

活动与规则:
  campaign                 触达活动定义（含 next_trigger_at / version）
  campaign_rule            活动圈选规则（JSON）
  campaign_execution       活动执行实例（防止中断丢失）

触达与点击:
  delivery_task            触达任务（含 dedup_key / trigger_event_id）
  delivery_record          触达发送记录
  click_event              点击事件

归因与补偿:
  attribution_task         归因等待任务
  attribution_record       归因结果
  data_compensation_task   数据补偿任务（Redis 重建 + 决策重试）
```

### 4.2 新增及变更表 DDL

```sql
-- 活动执行实例（防止定时活动执行中断）
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
) ENGINE=InnoDB;

-- 数据补偿任务（补偿队列 MySQL 持久化）
CREATE TABLE data_compensation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,     -- 当前版本仅 EVENT_REPLAY
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
) ENGINE=InnoDB;
```

补偿任务重复写入时显式恢复（而非仅重置 retry_count）：
```sql
INSERT INTO data_compensation_task (event_id, task_type, payload, status, next_retry_at, last_error)
VALUES (?, 'EVENT_REPLAY', ?, 'PENDING', NOW(), ?)
ON DUPLICATE KEY UPDATE
    status = 'PENDING',
    retry_count = 0,
    next_retry_at = NOW(),
    locked_at = NULL,
    last_error = VALUES(last_error);

**变更表**（对 v2.0 DDL 的修改）：

```sql
-- delivery_task 新增 dispatch 字段（轻量 Outbox 模式）+ processing_at（PROCESSING 超时恢复）
ALTER TABLE delivery_task
    ADD COLUMN dispatch_status ENUM('PENDING','PUBLISHED') DEFAULT 'PENDING',
    ADD COLUMN published_at DATETIME,
    ADD COLUMN processing_at DATETIME,
    ADD KEY idx_dispatch (dispatch_status, created_at),
    ADD KEY idx_processing (status, processing_at);

-- user_event: 新增 effective_event_time 字段（所有业务计算基准）
-- 建表时须包含:
--   effective_event_time DATETIME(3) NOT NULL,
--   KEY idx_effective_time (effective_event_time)

-- user_metric_hourly: metric_hour 使用 DATETIME 类型
-- （建表时直接使用 DATETIME NOT NULL，不再使用 VARCHAR）

---

## 五、项目结构

```
pulseflow/
├── pom.xml
├── pulseflow-common/                    # 通用接口与模型
├── pulseflow-event/                     # 行为事件接入与消费
│   └── service/
│       └── EventConsumer.java           # 含补偿任务写入逻辑
├── pulseflow-profile/                   # 用户画像与标签
├── pulseflow-campaign/                  # 决策引擎、触达与归因
│   ├── decision/
│   │   └── DecisionEngine.java
│   ├── delay/
│   │   └── DelayedTaskManager.java
│   ├── delivery/
│   │   ├── DeliveryService.java
│   │   ├── DeliveryConsumer.java
│   │   └── FrequencyControlService.java
│   └── attribution/
│       ├── AttributionService.java
│       └── AttributionTaskConsumer.java
├── pulseflow-job/                       # 定时任务（9个）
│   └── handler/
│       ├── WindowMetricJob.java
│       ├── DailyMetricJob.java
│       ├── TagRecalcJob.java
│       ├── CampaignSelectionJob.java
│       ├── RetryCompensationJob.java
│       ├── CompensationJob.java         # EVENT_REPLAY: 重建Redis + 重试决策
│       ├── DelayTaskRecoveryJob.java
│       ├── DispatchRetryJob.java       # Kafka 投递失败补偿
│       └── DataCleanupJob.java
├── pulseflow-simulator/                 # 事件模拟器
└── pulseflow-boot/                      # 启动与配置（聚合入口）
```

模块依赖：`common ← event/profile/campaign → job → boot`，boot 横向聚合。

---

## 六、CDP 框架组件映射

### MVP 阶段（8 个组件）

| CDP 组件 | PulseFlow 使用场景 |
|----------|-------------------|
| **base-stream** | Kafka 事件生产/消费，以 userId 为 Key 分区 |
| **base-job** | 9 个 XXL-JOB 任务（含补偿、恢复、Outbox 重发） |
| **base-cache** | 活动规则、消息模板缓存（Caffeine 本地，不用于实时状态） |
| **base-lock** | 标签重算并发控制，防止多节点重复 |
| **base-flyway** | 14 张表 DDL 演进 |
| **com-auth** | 管理端认证 |
| **biz-message** | 站内信 + 模拟 Push 渠道（taskId 作为业务键幂等） |
| **biz-log** | 决策审计日志 |

### Stage 2 扩展（4 个组件）

`biz-fulltext` / `base-sharding` / `base-export` / `base-cotime`，触发条件同 v2.0。

---

## 七、MVP 执行路线图

| Phase | 内容 | 工期 |
|-------|------|:--:|
| 1 | 骨架 + 行为管道（含 Kafka userId 分区 + 补偿任务写入） | 1.5 周 |
| 2 | 三级画像 + 标签（含日桶全量覆盖 + 策略模式 8 规则） | 1.5 周 |
| 3 | 决策引擎 + 触达（含 campaign_execution + Lua 频控 + 渠道幂等） | 2 周 |
| 4 | 归因 + 模拟器 + 补偿恢复（含 CompensationJob + 故障测试） | 1.5 周 |
| 5 | Testcontainers 测试 + 打磨 | 1 周 |

**总工期：7-8 周**。

---

## 八、关键风险与对策

| 风险 | 对策 |
|------|------|
| Redis 更新失败（补偿队列不可写） | MySQL `data_compensation_task` 持久化 |
| Redis 失败导致决策丢失 | `EVENT_REPLAY` 补偿任务 + dedup_key UK 防重 |
| 定时活动执行中断 | `campaign_execution` 实例表 + CampaignSelectionJob 内置恢复 |
| 同一用户事件乱序 | Kafka userId 分区键 + eventTime 校验 |
| 频控并发绕过 | Lua 原子判超限+自增+TTL |
| 同一天执行两次被误判重复 | 定时 dedup_key 使用 `{executionId}:{userId}` |
| 渠道发送成功但 DB 失败 | 站内信/Push 用 business_key UK 幂等；邮件诚实说明局限性 |

---

## 九、简历表述（最终版）

### 项目名称

**PulseFlow — 实时用户行为决策引擎**

### 技术栈

Spring Boot 3 · MyBatis-Plus · Kafka · Redis · Redisson · MySQL · XXL-JOB · Sa-Token · Flyway

### 项目描述

面向内容与电商增长场景，构建用户行为采集、实时状态计算、规则决策、自动动作执行及转化归因的事件驱动处理链路。

### 项目亮点

- 通过 **MySQL 本地事务与 Redis Lua 分层处理**实现消费幂等：事件归档与指标桶同事务提交，Redis 实时状态通过事件标记完成原子判重与更新；Redis 故障时写入 MySQL 补偿任务异步恢复，并触发决策重试保证关键路径不丢决策
- 设计实时状态、窗口指标和长期标签**三级用户画像体系**，Lua 原子更新 Redis 跨日计数与购物车状态，基于时间桶聚合与 XXL-JOB 分片任务计算滑动窗口指标，策略模式扩展 8 种可插拔标签规则
- 实现即时事件、延迟任务和批量定时**三种触发模式**；基于 Redis ZSET 与 Lua 完成延迟任务 pending→processing 原子领取与超时恢复；定时活动通过统一扫描 + 乐观锁抢占 + `campaign_execution` 实例表防止执行中断
- 设计**点击后 LAST-TOUCH 转化归因链路**，通过持久化归因等待任务 + Redis 宽限窗口处理迟到点击事件，结合客户端时间偏差校验降级策略、事件时序校验及目标事件唯一约束，避免同一次转化被多个活动重复归因
- 实现 **Lua 原子频控**（判超限+自增+TTL 一步完成）防止并发绕过，触达任务**业务去重键**机制防止重复创建，内部渠道通过业务键保证发送幂等

---

## 十、附录

### Kafka Topic 规划

| Topic | 用途 | 分区数 | Key |
|-------|------|:-----:|-----|
| pulseflow.raw.events | 原始行为事件 | 4 | userId |
| pulseflow.delivery | 触达发送任务 | 2 | userId |

### Redis Key 规划

| Key Pattern | 用途 | TTL |
|-------------|------|:--:|
| `event:processed:{eventId}` | 事件处理标记 | 604800s (7d) |
| `user:rt:{userId}` | 长期实时状态(Hash) | — |
| `user:daily:{userId}:{yyyyMMdd}` | 当日计数(Hash) | 48h |
| `user:cart:{userId}` | 购物车(Hash) | — |
| `user:window:{userId}` | 窗口指标缓存(Hash) | 3600s |
| `delay:pending:{taskType}` | 延迟任务待执行(ZSET) | — |
| `delay:processing:{taskType}` | 延迟任务处理中(ZSET) | — |
| `delay:attribution` | 归因宽限等待(ZSET) | — |
| `freq:user:{userId}:{date}` | 用户日频控(String) | 86400s |
| `freq:campaign:{campaignId}:{userId}` | 活动周频控(String) | 604800s |
| `freq:reserved:{taskId}` | 触达任务频控占用标记(String) | 86400s (24h) |

### dedup_key 生成规则

| 触发类型 | dedup_key 格式 | 示例 |
|----------|---------------|------|
| 事件触发 | `{campaignId}:{userId}:{eventId}` | `5:1024:evt_001` |
| 延迟触发 | `{campaignId}:{userId}:{cartItemId}:{addCartEventId}` | `5:1024:ci_501:evt_100` |
| 定时圈选 | `{campaignExecutionId}:{userId}` | `42:1024` |

---

> **文档维护**: 本文件随项目推进持续更新。修订历史：v1.0 初始方案 → v2.0 修订幂等链路、去重键、归因持久化、时间校验、调度模式、表数量 → v2.1 修订补偿队列 MySQL 化、Redis 失败决策重试、Kafka userId 分区、campaign_execution 防中断、Lua 频控、渠道幂等、dedup_key 格式修正 → v2.2 修订 DuplicateKeyException 不直接 ACK、补偿任务合并 EVENT_REPLAY、购物车拆分独立 Hash、频控语义简化、execution 与 next_trigger 同事务、delivery_task Outbox 模式、metric_hour/scheduled_at 改为 DATETIME → v2.3 补偿任务 PROCESSING 超时恢复、重复事件从 DB 读标准事件、CampaignSelectionJob 统一执行恢复、重试不重复占频控额度、触达 PROCESSING 超时恢复、effective_event_time 统一时间、9 个 XXL-JOB 任务。
