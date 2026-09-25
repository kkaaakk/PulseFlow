# PulseFlow — 当前架构与 Agent 接入边界

PulseFlow Java 是确定性业务系统。它保存事件和用户画像，执行 Campaign 规则、频控、触达、归因，并计算人群与 Campaign 绩效。Pydantic AI 尚未接入；下一阶段独立 Python Agent Service 才负责 LLM、调查、假设、证据和结构化输出。

```text
未来 Python Agent Service
         │ HTTP Tool Calls
         ▼
PulseFlow Java Business APIs
   ├─ Profile / Metrics
   ├─ Campaign DSL Validation / Audience Preview
   ├─ Campaign Draft / Confirm
   ├─ Campaign Performance
   └─ Attribution
         │
         ▼
   MySQL / Redis / Kafka
```

Java 不直接请求 LLM，也没有 Prompt Runtime 或模型 JSON 解析层。未来提交的 Campaign Proposal 必须经过 Java 的字段、类型、操作符、时间、频控和优惠事实校验；Sa-Token 身份、归属校验和人工确认仍由 Java 执行。

# 三、五条核心确定性业务链路

## 链路 1：行为接入与幂等消费

### 3.1.1 接入流程

```text
POST /api/events
  ↓
校验 eventId / userId / eventType / eventTime 等字段
  ↓
计算 receivedAt / effectiveEventTime / clockSkew
  ↓
以 userId 作为 Kafka Key
  ↓
pulseflow.raw.events
```

同一用户事件以 `userId` 作为 Kafka Key，目的是尽量保证同一用户在同一分区内的事件顺序。

### 3.1.2 EventConsumer 三阶段

当前 `EventConsumer` 把消费流程拆成三阶段：

```text
Kafka raw event
  │
  ├─ Phase 1：MySQL 事实事务
  │    ├─ INSERT user_event
  │    └─ UPSERT user_metric_hourly
  │
  ├─ Phase 2：Redis 实时画像 Lua
  │    ├─ event:processed:{eventId} 幂等判断
  │    ├─ 更新 user:rt / user:daily / user:cart
  │    └─ 写 processed flag
  │
  └─ Phase 3：DecisionEngine
       ├─ EVENT Campaign 即时决策
       ├─ DELAYED Campaign 延迟任务创建
       └─ 命中后创建 delivery_task
```

### 3.1.3 MySQL 幂等语义

`user_event.event_id` 有唯一约束。

重复事件到达时：

```text
DuplicateKeyException
  ≠ “整条业务已经完成”

它只代表：
这个 eventId 已经至少成功写入 MySQL 一次。
```

因此重复消费不能直接认定 Redis 和 DecisionEngine 也处理过。

当前实现会从 MySQL 读取该 `eventId` 对应的**标准事件记录**，后续 Redis 和决策阶段继续使用数据库中的 canonical event，而不是相信新的 Kafka 重放 payload。

### 3.1.4 Redis 实时更新

核心思想：

```text
if event:processed:{eventId} exists:
    skip
else:
    update realtime metrics
    set processed flag
```

典型 Key：

```text
user:rt:{userId}
user:daily:{userId}:{yyyyMMdd}
user:cart:{userId}
event:processed:{eventId}
```

事件处理标记 TTL 为 7 天，用来阻止 Kafka 重放导致 Redis 指标重复累计。

### 3.1.5 补偿机制

如果 Phase 2 或 Phase 3 失败：

```text
data_compensation_task
  task_type = EVENT_REPLAY
  status = PENDING
```

`CompensationJob` 后续固定重放：

```text
EVENT_REPLAY
  ↓
重新执行 Redis 实时状态更新（幂等）
  ↓
重新执行 DecisionEngine
  ↓
delivery_task.dedup_key 再次兜底
```

如果连补偿任务都无法落 MySQL，则消费逻辑抛异常，让 Kafka 有机会再次投递。

### 3.1.6 转化事件旁路

当前 `ORDER_PAID` 同时被视为归因目标事件。

当事件主处理阶段完成后，会调用 `AttributionService.onTargetEvent(...)` 创建归因等待任务，使行为事件链能够继续进入链路 5。

---

## 链路 2：三级用户画像计算

PulseFlow 不把用户画像做成单一字段，而是拆成三层：

```text
实时状态
+
窗口指标
+
长期标签
```

### 3.2.1 第一层：实时状态 Redis

```text
user:rt:{userId}
  last_login_at
  last_active_at

user:daily:{userId}:{yyyyMMdd}
  views
  search_count

user:cart:{userId}
  cartItemId -> JSON
```

事件消费时实时更新，适合 DecisionEngine 的即时判断。

### 3.2.2 第二层：窗口指标

底层先把行为写成小时桶：

```text
user_metric_hourly
```

然后通过 XXL-JOB 聚合为：

```text
user_metric_daily
user_behavior_summary
```

当前窗口指标用于表达类似：

```text
search_1h
active_7d
spend_30d
fav_7d
```

其价值是避免每次规则判断都从原始 `user_event` 扫描大时间范围。

### 3.2.3 第三层：长期标签

`TagRecalcJob` 根据窗口指标和标签策略周期性计算：

```text
AI_PREF
HIGH_VALUE
CHURN_RISK
PRICE_SENSITIVE
...
```

结果写入：

```text
user_tag
```

### 3.2.4 ProfileService 对上层提供统一查询

Campaign 与 AI 层不需要自己理解底层所有表，而是通过 Profile 相关服务访问：

```text
hasTag(...)
getMetricValue(...)
getWindowMetrics(...)
getUserTags(...)
```

这样实时指标、窗口指标和长期标签可以作为一个统一用户画像能力被 DecisionEngine 和 AI 聚合层复用。

---

## 链路 3：Campaign 决策与三种触发模式

Campaign 有三种触发模式：

```text
EVENT
DELAYED
SCHEDULED
```

### 3.3.1 EVENT：事件即时触发

```text
EventConsumer Phase 3
  ↓
DecisionEngine.evaluate(event)
  ↓
查找 ACTIVE + EVENT Campaign
  ↓
按 eventType 匹配
  ↓
加载 CampaignRule
  ↓
读取 Profile / Event 条件
  ↓
规则全部匹配
  ↓
UserPreferenceService 快速预过滤
  ↓
生成 dedup_key
  ↓
DeliveryService.createDeliveryTask(...)
```

预过滤包括免打扰、退订、已转化等不应该继续创建触达任务的场景。

### 3.3.2 DELAYED：延迟触发

典型场景：加购后一定时间仍未购买。

```text
ADD_CART
  ↓
DecisionEngine 匹配 DELAYED Campaign
  ↓
生成 delayed task id
  ↓
Redis ZSET pending
  ↓
到期后 Lua 原子 pending → processing
  ↓
DelayedTaskExecutor 再检查条件
  ↓
条件仍满足 → 创建 delivery_task
条件已失效 → 取消
```

延迟任务通过 Redis ZSET 实现时间排序，并有 processing 区和恢复任务防止执行中途宕机造成任务永久丢失。

### 3.3.3 SCHEDULED：定时圈选

定时 Campaign 不会给每个活动动态创建一个 XXL-JOB。

系统使用统一：

```text
CampaignSelectionJob
```

流程：

```text
扫描到期 ACTIVE + SCHEDULED Campaign
  ↓
推进 next_trigger_at + version
  ↓
创建 campaign_execution(PENDING)
  ↓
CAS：PENDING → RUNNING
  ↓
分页扫描 ACTIVE 用户
  ↓
DecisionEngine.evaluateBatch(...)
  ↓
命中 → delivery_task
  ↓
execution DONE
```

`campaign_execution` 把“一次 Campaign 调度”实体化，使某一轮定时圈选具备独立状态和 dedup 语义。

定时 Campaign 的去重键为：

```text
{campaignExecutionId}:{userId}
```

因此同一天允许有不同 execution，不会被简单“按日期去重”误伤。

### 3.3.4 决策异常传播约定

当前 `DecisionEngine` 区分：

```text
业务可跳过异常
  → 内部消化

基础设施异常（DB / Redis / Kafka 等）
  → 向上抛
```

事件触发场景由 `EventConsumer` 将基础设施失败转成 `EVENT_REPLAY` 补偿；定时场景则由 `CampaignSelectionJob` 将 execution 重新置为待重试状态。

---

## 链路 4：触达执行与频控

这是旧文档里容易被 AI 新链路遮掉的一条关键核心链路。

### 3.4.1 DeliveryTask 创建

DecisionEngine 不直接发消息，只创建：

```text
delivery_task
```

并生成业务去重键：

| 类型 | dedup_key |
|---|---|
| EVENT | `{campaignId}:{userId}:{eventId}` |
| DELAYED | `{campaignId}:{userId}:{cartItemId}:{addCartEventId}` |
| SCHEDULED | `{campaignExecutionId}:{userId}` |

`delivery_task.uk_dedup` 保证同一个业务动作不会重复创建任务。

### 3.4.2 轻量 Outbox 投递

`DeliveryService`：

```text
INSERT delivery_task
  status = PENDING
  dispatch_status = PENDING
  ↓
发送 Kafka pulseflow.delivery
  ↓
Broker ACK 成功
  ↓
dispatch_status = PUBLISHED
```

如果 Kafka 投递失败，数据库中的任务仍保留为 `dispatch_status=PENDING`，由 `DispatchRetryJob` 后续重发。

### 3.4.3 DeliveryConsumer 抢占

消费者拿到任务后不会直接发送，而是先：

```text
UPDATE delivery_task
SET status = PROCESSING
WHERE id = ? AND status = PENDING
```

只有影响行数为 1 的消费者实例继续执行。

这解决重复 Kafka 消息或多消费者并发情况下的重复处理问题。

### 3.4.4 Lua 原子频控

发送前调用 `FrequencyControlService`。

使用三个 Redis Key：

```text
freq:user:{userId}:{date}
freq:campaign:{campaignId}:{userId}
freq:reserved:{taskId}
```

Lua 在一次原子操作内完成：

```text
检查 task 是否已经占过额度
  ↓
检查用户日频控
  ↓
检查 Campaign 周频控
  ↓
INCR 两级计数
  ↓
写 freq:reserved:{taskId}
```

因此两个并发实例不会同时“检查通过后一起加一”。

`freq:reserved:{taskId}` 还保证同一个 delivery task 在失败重试时只消耗一次频控额度。

### 3.4.5 渠道幂等

当前支持：

```text
IN_APP
PUSH
EMAIL（MVP 模拟）
```

站内信：

```text
in_app_message.business_key = taskId
UNIQUE KEY
```

模拟 Push：

```text
push_record.business_key = taskId
UNIQUE KEY
```

因此如果“渠道写入成功，但 delivery_task 状态更新前进程宕机”，重试时命中业务唯一键，不会生成第二条站内信/Push。

EMAIL 当前仍属于 MVP 模拟路径。真实外部 SMTP 无法仅依赖本地数据库做到绝对 exactly-once，未来接真实邮件服务时需要结合供应商业务幂等能力进一步加强。

### 3.4.6 失败与恢复

```text
渠道发送失败
  ↓
delivery_task = WAIT_RETRY
retry_count + 1
next_retry_at = backoff time
```

`RetryCompensationJob` 负责恢复：

```text
WAIT_RETRY 到期
  ↓
PENDING + dispatch_status=PENDING
  ↓
DispatchRetryJob 再投 Kafka
```

同时会处理长时间卡在 `PROCESSING` 的任务，超过最大重试次数后进入 `FAILED`。

---

## 链路 5：点击与转化归因

### 3.5.1 当前归因模型

```text
CLICK_LAST_TOUCH
```

目标：一个转化事件只归因给归因窗口内最近一次有效点击。

### 3.5.2 完整流程

```text
消息发送
  ↓
delivery_record
  ↓
用户点击
  ↓
click_event
  ↓
ORDER_PAID 等目标事件进入 EventConsumer
  ↓
attribution_task
  ↓
Redis delay:attribution 等待宽限窗口
  ↓
AttributionTaskConsumer
  ↓
查询 24h 内有效 click_event
  ↓
过滤 click_time > sent_at
     click_time < target_event_time
  ↓
选择最近一条 click
  ↓
attribution_record
```

### 3.5.3 防重复归因

```text
attribution_record.uk_target_event_id
```

保证同一个目标转化事件最多生成一条归因记录。

### 3.5.4 为什么有宽限窗口

Kafka userId 分区只能减少同一来源事件乱序，不能完全消灭客户端网络延迟、跨来源到达时间差等问题。

因此目标转化事件到达后不会立刻最终归因，而是先进入等待队列，给稍晚到达的点击事件一个短暂宽限时间。

---


# 四、Campaign 规划与分析

`com.pulseflow.campaign.dsl` 定义 Campaign DSL。`CampaignDslValidator`、`CampaignFieldRegistry` 和 `DslToRuleConverter` 将合法条件转为 `CampaignRule`。`SqlAudiencePreviewService` 根据画像快照给出 `estimatedCount`、`dataVersion` 与 warnings。`CampaignDraftService` 管理草稿编辑、过期、预估刷新、归属和幂等确认；REST 路径为 `/api/campaign-drafts`。`CampaignContentValidator` 检查长度、禁词、PII 模式与优惠事实。

`AudienceMetricsAggregator` 计算聚合人群指标。`PerformanceSummaryCalculator` 读取 Delivery、Click 和 Attribution，持久化 `campaign_performance_summary`。`CampaignPerformanceSummaryJob` 仅调用该确定性计算器。指标公式、精度、空值和基线定义见 [指标口径字典](docs/campaign-metrics-glossary.md)。

# 五、依赖与持久化

Maven 模块为 `common`、`event`、`profile`、`campaign`、`job`、`simulator`、`boot`。Campaign 可以读取 Profile 和公共 Mapper；Profile 不依赖 Campaign，因此没有循环依赖。Job 依赖 Campaign，Boot 汇总各业务模块。仓库不再包含 Java AI 模块。

Flyway V1–V5 为不可变历史。`campaign_ai_draft` 仍承载草稿，`campaign_performance_summary` 仍承载绩效摘要；`ai_generation_record`、`campaign_ai_review` 和 `campaign_content_variant` 暂时只保留表结构，不再有 Java runtime 读写。后续 schema 清理由独立 migration 完成。

# 六、可靠性与验证

事件 `eventId` 幂等、Kafka at-least-once、Redis 补偿、Campaign 规则决策、频控 Lua 原子配额、Delivery 去重和 Last-Touch 归因的业务语义保持不变。测试与 CI 流程见 [工程验证](docs/engineering-verification.md)，本轮移动与删除明细见 [Preparation Report](docs/agent/pydantic-ai-preparation-report.md)。
