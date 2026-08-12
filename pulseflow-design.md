# PulseFlow — 实时用户行为决策引擎 + AI Campaign Copilot 设计方案

> **版本**：3.0  
> **文档日期**：2026-08-12  
> **代码基线**：`kkaaakk/PulseFlow` `main` @ `a2215b13f9ea0c61b0b6c7be68aaa0fcf92c1bdd`  
> **状态**：核心业务闭环 + AI Campaign Copilot 已实现，CI 全绿

---

## 一、项目定位

### 1.1 一句话描述

PulseFlow 是一个面向内容社区与电商增长场景的**事件驱动实时用户行为决策引擎**：系统采集用户行为，实时更新用户画像，按 Campaign 规则进行决策，自动创建并执行触达任务，最后通过点击与转化归因形成闭环；在此基础上增加 **AI Campaign Copilot**，用于辅助 Campaign 创建、人群洞察、营销文案生成和活动复盘。

### 1.2 核心原则

PulseFlow 的核心不是“AI 自动运营”，而是：

```text
确定性 Java 业务引擎负责执行
+
AI 负责理解、解释、建议和复盘
```

AI 可以：

- 把自然语言运营需求转换成受约束的 Campaign DSL；
- 对目标人群的聚合画像做解释；
- 根据真实优惠事实生成营销文案；
- 基于后端真实计算的活动指标生成复盘。

AI 不可以：

- 直接执行 SQL；
- 直接修改用户画像；
- 绕过 Campaign 规则校验；
- 绕过频控和去重；
- 自动激活 Campaign；
- 直接触发消息发送；
- 向模型发送用户级完整行为轨迹和敏感个人信息。

### 1.3 当前完整闭环

```text
用户行为
  ↓
Kafka 事件流
  ↓
MySQL 事实落盘 + Redis 实时状态
  ↓
三级用户画像
  ↓
Campaign 规则决策
  ↓
触达任务
  ↓
Kafka Delivery
  ↓
频控 + 渠道发送
  ↓
点击 / 目标转化事件
  ↓
Last-Touch 转化归因
  ↓
Campaign Performance Summary
  ↓
AI Campaign Review
```

同时，Campaign 创建侧存在一条辅助链路：

```text
自然语言运营需求
  ↓
AI Campaign DSL
  ↓
字段白名单 / 类型 / 操作符 / 业务边界校验
  ↓
人群预估 + 人群洞察 + 文案建议
  ↓
人工确认
  ↓
正式 Campaign（DRAFT）
  ↓
仍然进入原有确定性 Campaign 引擎
```

---

# 二、整体架构

## 2.1 当前逻辑架构

```text
┌──────────────────────────────────────────────────────────────┐
│                         PulseFlow                            │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  行为接入层                                                   │
│  REST API → Kafka(pulseflow.raw.events)                      │
│                 │                                            │
│                 ▼                                            │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ 事件处理                                             │    │
│  │ MySQL 事实落盘 → Redis 实时画像 → DecisionEngine     │    │
│  └───────────────┬──────────────────────────────────────┘    │
│                  │                                           │
│          ┌───────┴─────────┐                                 │
│          ▼                 ▼                                 │
│     用户画像体系        Campaign 决策                         │
│  实时/窗口/长期标签    EVENT/DELAYED/SCHEDULED                │
│                            │                                 │
│                            ▼                                 │
│                      delivery_task                           │
│                            │                                 │
│                            ▼                                 │
│                  Kafka(pulseflow.delivery)                   │
│                            │                                 │
│                            ▼                                 │
│                   Lua 频控 + 渠道发送                         │
│                            │                                 │
│                            ▼                                 │
│                     点击 / 转化归因                           │
│                            │                                 │
│                            ▼                                 │
│                   活动效果聚合与 AI 复盘                       │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│ AI Campaign Copilot                                          │
│                                                              │
│ 自然语言 → DSL → 草稿 → 人群洞察 → 文案 → 人工确认            │
│                                 │                            │
│                                 └──→ 原 Campaign 引擎执行     │
│                                                              │
│ Campaign 结束 → 效果摘要 → AI Review → 结构化复盘             │
└──────────────────────────────────────────────────────────────┘
```

## 2.2 当前基础设施职责

| 组件 | 当前职责 | 数据可靠性定位 |
|---|---|---|
| Kafka | 原始行为事件流、触达任务分发、削峰解耦 | 可重复投递，消费者必须幂等 |
| MySQL | 核心事实数据、Campaign、触达、归因、补偿、AI 草稿与审计 | **核心事实来源，不可丢** |
| Redis | 实时画像、事件处理标记、频控、延迟任务、归因宽限队列 | 可重建，允许通过补偿恢复 |
| XXL-JOB | 窗口指标、标签、Campaign 扫描、补偿恢复、AI 复盘等后台任务 | 调度入口 |
| Sa-Token | 管理端身份和 AI 资源访问控制 | 用户身份来源 |
| Flyway | MySQL Schema 演进 | V1～V5 |
| AI Provider | 结构化 DSL / Insight / Content / Review 生成 | 可关闭，不得成为核心业务单点 |

> **Elasticsearch 不是当前主链路已落地组件。** 旧版文档中的 ES 行为检索属于后续 Stage 2 扩展方向，不再画入当前已实现架构。

## 2.3 一致性原则

1. **MySQL 保存不可丢的业务事实。**
2. **Redis 保存可重建的实时状态。** Redis 更新失败时通过 `data_compensation_task` 恢复。
3. Kafka 消费路径必须允许重复执行，数据库 UK、Redis processed flag 和业务 dedup key 分层兜底。
4. Campaign 真正执行永远走确定性业务引擎，AI 不绕过规则、频控、幂等和发送链路。
5. AI 输出必须经过 Java 校验后才能进入业务系统。
6. AI 失败不得影响非 AI Campaign 主链路启动和运行。

---

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

# 四、四条 AI Campaign Copilot 链路

AI 是当前 PulseFlow 的增强层，共四条明确业务链路。

---

## AI 链路 1：自然语言 → Campaign DSL → AI Draft → 正式 Campaign

### 4.1.1 输入

运营人员可以输入类似：

```text
筛选最近7天活跃不少于5天、最近30天消费超过500元的用户，
今晚8点通过站内信发送满300减30优惠，
每个用户24小时最多触达一次。
```

### 4.1.2 CampaignIntentService

处理流程：

```text
自然语言
  ↓
SensitiveDataSanitizer
  ↓
CampaignIntentPromptBuilder
  ↓
AiModelClient.generateStructured
  ↓
AiOutputParser
  ↓
CampaignDsl
  ↓
CampaignDslValidator
  ↓
AudiencePreviewService
  ↓
campaign_ai_draft
```

### 4.1.3 DSL Guardrail

AI 不是自由生成执行代码，而是输出受约束 DSL。

Java 侧检查：

```text
字段白名单
字段类型
操作符
数值上下界
规则层级
条件数量
渠道枚举
时间格式
频控范围
优惠事实
missingFields
warnings
```

只有通过校验的 DSL 才能进入 `VALIDATED`。

### 4.1.4 Draft 状态机

```text
GENERATED / NEEDS_CONFIRMATION / VALIDATED
        │
        ├─ operator edit → 重新校验
        │
        ├─ 超时 → EXPIRED
        │
        └─ VALIDATED + confirm
                  ↓
               CONFIRMED
```

Draft 默认有 TTL，当前配置为 24 小时。

### 4.1.5 人工确认后创建 Campaign

`confirmAndCreate(...)` 会在事务中：

```text
重新校验 DSL
  ↓
INSERT campaign
  status = DRAFT
  created_by = operatorId
  ↓
DSL → CampaignRule
  ↓
INSERT campaign_rule
  ↓
Draft → CONFIRMED
```

重点：

```text
AI 确认 ≠ 自动发送
```

AI 创建的 Campaign 仍然是 `DRAFT`，必须进入原有 Campaign 生命周期，真正执行时仍由原有 DecisionEngine、频控、幂等和 Delivery 链路负责。

---

## AI 链路 2：目标人群预估与人群洞察

### 4.2.1 目标

让运营人员在正式确认 Campaign 前知道：

- 大概会命中多少用户；
- 这批用户有哪些明显画像特征；
- 与当前基线相比有什么差异；
- 哪些结论有真实指标支撑。

### 4.2.2 数据流

```text
campaign_ai_draft.dsl_json
  ↓
AudienceMetricsAggregator
  ↓
只计算聚合指标
  ↓
AudienceInsightPromptBuilder
  ↓
AiModelClient
  ↓
InsightResult(JSON)
  ↓
InsightEvidenceValidator
  ↓
返回聚合指标 + AI 洞察 + DataQuality
```

### 4.2.3 隐私边界

模型只接收：

```text
人数
比例
均值
标签分布
聚合基线
```

不发送：

```text
用户姓名
手机号
地址
身份证
单个用户完整行为轨迹
用户级订单明细
设备唯一标识
```

### 4.2.4 Evidence 校验

AI 输出的 finding 必须引用后端输入中真实存在的 `evidenceKeys`。

如果模型编造了不存在的证据，Java 校验层会丢弃或拒绝该输出，而不是直接展示给运营人员。

### 4.2.5 当前数据质量说明

当前 v1 会同时返回 `DataQuality`，明确告诉前端：

- baseline 目前使用候选池口径；
- `cartWithoutPurchaseRate` 等部分指标是代理口径；
- `topCategories`、`memberLevelDistribution` 当前不可用。

因此 AI 不应该把这些缺失数据包装成“确定事实”。

---

## AI 链路 3：策略与营销文案生成

### 4.3.1 输入来源

文案生成不会信任前端重新传入优惠金额。

真正的优惠事实来自：

```text
campaign_ai_draft.dsl_json.promotionFacts
```

也就是服务器端已经保存并校验过的 Campaign DSL。

### 4.3.2 数据流

```text
Draft DSL
  ↓
objective / channel / audienceSummary / promotionFacts
  ↓
tone / length / forbiddenWords
  ↓
CampaignContentPromptBuilder
  ↓
AiModelClient
  ↓
ContentResult(JSON)
  ↓
ContentFactValidator
  ↓
可用文案 variants
```

默认可以生成多个差异化版本，例如：

```text
DIRECT_BENEFIT
URGENCY
PERSONALIZED
```

### 4.3.3 ContentFactValidator

Java 层负责检查：

```text
标题长度
正文长度
禁用词
模板结构
金额和折扣数字
优惠事实一致性
PII
虚构紧迫性
有效期
```

例如系统真实优惠是“满 300 减 30”，模型不能改成“满 200 减 50”。

这保证大模型负责表达，而不是负责定义事实。

---

## AI 链路 4：Campaign 效果摘要与 AI 活动复盘

这是旧版主设计文档没有同步进去的当前关键链路。

### 4.4.1 触发入口

新增：

```text
CampaignReviewJob
```

Job 会扫描最近一段时间已经结束的 Campaign。当前代码的 look-back window 为 72 小时。

### 4.4.2 Performance Summary 由后端计算

在调用 AI 前先计算：

```text
campaign_performance_summary
```

指标包括：

```text
targetAudienceCount
sentCount
deliveredCount
clickedCount
convertedCount
unsubscribeCount

deliveryRate
clickRate
conversionRate
unsubscribeRate
```

这些指标由后端事实表计算，AI 只读，不让模型自己猜数字。

### 4.4.3 数据成熟度判断

在调用 AI 之前先判断数据是否足够：

```text
目标人数 = 0
  → SKIPPED_INSUFFICIENT_DATA

目标人数 > 0，但 sentCount = 0，仍处于数据归集宽限期
  → DATA_NOT_READY
  → next_retry_at 后再试

宽限期结束仍无发送数据
  → SKIPPED_INSUFFICIENT_DATA

数据充分
  → 调用 AI Review
```

这样可以避免刚结束的 Campaign 因触达数据尚未聚合完成，就被永久误判为“无效果”。

### 4.4.4 Review 并发状态机

`campaign_ai_review` 当前不是简单 SUCCESS / FAILED，而是有明确状态机：

```text
PENDING
  ↓ CAS 抢占
PROCESSING
  ├─ AI 成功 → SUCCESS
  ├─ 暂时失败 → RETRYABLE_FAILED
  ├─ 数据未成熟 → DATA_NOT_READY
  ├─ 永久数据不足 → SKIPPED_INSUFFICIENT_DATA
  └─ 重试耗尽 / 永久错误 → PERMANENT_FAILED
```

多个 XXL-JOB executor 同时扫描同一个 Campaign 时，通过条件 UPDATE 抢 `PROCESSING` 锁，只有一个节点真正调用大模型，防止重复 AI 成本。

### 4.4.5 可重试失败

AI Provider timeout、5xx 或可重试输出错误：

```text
RETRYABLE_FAILED
retry_count + 1
next_retry_at = exponential backoff
```

超过最大重试次数后：

```text
PERMANENT_FAILED
```

### 4.4.6 Evidence 校验

AI Review 输出完成后还会经过 `ReviewEvidenceValidator`。

因此最终复盘必须围绕后端真实计算的 summary 指标，而不是让模型凭空生成“转化提升 30%”之类的结论。

### 4.4.7 最终闭环

```text
Campaign 执行
  ↓
Delivery / Click / Attribution 事实数据
  ↓
PerformanceSummaryCalculator
  ↓
campaign_performance_summary
  ↓
CampaignReviewJob
  ↓
CampaignReviewService
  ↓
AI Review + Evidence Validator
  ↓
campaign_ai_review
```

至此 PulseFlow 从“实时行为 → 决策 → 触达 → 归因”扩展成了完整的 Campaign 生命周期：

```text
创建前：AI 理解运营意图
创建中：AI 洞察人群、辅助生成内容
执行时：确定性 Java 引擎负责真实决策与发送
结束后：AI 基于真实指标复盘
```

---

# 五、数据库设计

## 5.1 当前 Flyway 版本

```text
V1__init.sql
V2__channel_tables.sql
V3__ai_campaign_tables.sql
V4__ai_review_state_machine.sql
V5__review_status_split_and_ownership.sql
```

## 5.2 当前共 21 张物理表

### 用户、行为、画像：6 张

```text
user_profile
user_event
user_metric_hourly
user_metric_daily
user_behavior_summary
user_tag
```

### Campaign：3 张

```text
campaign
campaign_rule
campaign_execution
```

### 触达：4 张

```text
delivery_task
delivery_record
in_app_message
push_record
```

### 归因：3 张

```text
click_event
attribution_task
attribution_record
```

### 补偿：1 张

```text
data_compensation_task
```

### AI Campaign Copilot：4 张

```text
campaign_ai_draft
ai_generation_record
campaign_performance_summary
campaign_ai_review
```

总计：

```text
15（V1） + 2（V2） + 4（V3） = 21 张
```

V4、V5 不新增表，而是增强 `campaign_ai_review` 状态机并给 `campaign` 增加 ownership 字段。

## 5.3 AI 表职责

### campaign_ai_draft

保存：

```text
自然语言原始需求
Campaign DSL
校验状态
校验错误 / warnings
预估人群数量
确认后的 campaignId
operatorId
```

### ai_generation_record

保存每次 AI 调用审计：

```text
requestId
taskType
provider / model
promptVersion
脱敏后的输入
结构化输出
Token
Latency
ErrorCode
Draft / Campaign 关联
```

### campaign_performance_summary

保存后端真实计算的 Campaign 效果指标。

### campaign_ai_review

保存 AI Review、状态机、锁、失败类型和重试信息。

---

# 六、Kafka 与 Redis 设计

## 6.1 Kafka Topic

| Topic | 用途 | Key |
|---|---|---|
| `pulseflow.raw.events` | 用户行为事件 | `userId` |
| `pulseflow.delivery` | 触达任务分发 | `userId` |

`userId` 作为 Key 的主要目的，是让同一用户尽量进入同一分区，减少行为状态乱序。

## 6.2 Redis Key

| Key | 用途 | TTL / 备注 |
|---|---|---|
| `event:processed:{eventId}` | Redis 事件处理幂等标记 | 7d |
| `user:rt:{userId}` | 长期实时状态 | 无固定 TTL |
| `user:daily:{userId}:{yyyyMMdd}` | 当日实时计数 | 约 48h |
| `user:cart:{userId}` | 当前购物车状态 | Hash |
| `user:window:{userId}` | 窗口指标缓存 | 短 TTL |
| `delay:pending:{taskType}` | 延迟任务待执行 | ZSET |
| `delay:processing:{taskType}` | 延迟任务处理中 | ZSET |
| `delay:attribution` | 归因宽限等待 | ZSET |
| `freq:user:{userId}:{date}` | 用户日频控 | 24h |
| `freq:campaign:{campaignId}:{userId}` | Campaign 周频控 | 7d |
| `freq:reserved:{taskId}` | 单任务频控占用标记 | 24h |

---

# 七、XXL-JOB 任务体系

当前 `pulseflow-job` 中共有 **10 个 handler**：

| Job | 主要职责 |
|---|---|
| `WindowMetricJob` | 计算窗口指标 |
| `DailyMetricJob` | 小时桶聚合日桶 |
| `TagRecalcJob` | 重算长期标签 |
| `CampaignSelectionJob` | 定时 Campaign 扫描、execution 创建与执行恢复 |
| `RetryCompensationJob` | 触达 WAIT_RETRY / PROCESSING 恢复 |
| `CompensationJob` | EVENT_REPLAY：恢复 Redis + 重试决策 |
| `DelayTaskRecoveryJob` | 恢复卡死延迟任务 |
| `DispatchRetryJob` | 重新投递未 PUBLISHED 的 delivery task |
| `DataCleanupJob` | 清理历史过期数据 |
| `CampaignReviewJob` | 扫描结束 Campaign 并触发 AI 复盘 |

> XXL-JOB handler 的具体 cron/执行频率由 XXL-JOB Admin 配置；代码负责注册 handler 与实现任务语义，不应把文档中的建议频率误认为 Java 代码里的固定调度表达式。

---

# 八、当前 Maven 模块结构

当前一共 **8 个模块**：

```text
pulseflow/
├── pom.xml
│
├── pulseflow-common
│   ├── entity
│   ├── mapper
│   ├── enums
│   ├── dto
│   ├── util
│   └── exception
│
├── pulseflow-event
│   ├── controller/EventController
│   ├── service/EventService
│   ├── service/EventPersistenceService
│   └── consumer/EventConsumer
│
├── pulseflow-profile
│   ├── ProfileService
│   ├── UserPreferenceService
│   └── TagRule / tag strategy
│
├── pulseflow-campaign
│   ├── decision/DecisionEngine
│   ├── profile/RealtimeProfileUpdateService
│   ├── delay/DelayedTaskManager
│   ├── delay/DelayedTaskExecutor
│   ├── delivery/DeliveryService
│   ├── delivery/DeliveryConsumer
│   ├── delivery/FrequencyControlService
│   └── attribution/*
│
├── pulseflow-ai
│   ├── api
│   ├── application
│   ├── domain
│   ├── provider
│   ├── prompt
│   ├── guardrail
│   ├── infrastructure
│   └── support
│
├── pulseflow-job
│   └── handler/*        # 10 个 XXL-JOB handler
│
├── pulseflow-simulator
│   └── 事件模拟 / 演示入口
│
└── pulseflow-boot
    ├── PulseFlowApplication
    ├── config
    └── db/migration/V1~V5
```

## 8.1 模块依赖原则

核心方向：

```text
common
  ↑
profile / campaign / event
  ↑
ai（可选增强层）
  ↑
job / boot 聚合运行
```

其中 AI 模块实际依赖：

```text
pulseflow-common
pulseflow-profile
pulseflow-campaign
```

重要约束：

```text
pulseflow-campaign 不反向依赖 pulseflow-ai
```

因此关闭 AI 后，原始行为决策主链仍能独立工作。

---

# 九、AI Provider 与 Guardrail 架构

## 9.1 Provider 抽象

```text
AiModelClient
├── OpenAiCompatibleClient
└── FakeAiModelClient
```

`OpenAiCompatibleClient` 用于真实 OpenAI-compatible Provider；`FakeAiModelClient` 用于本地、测试和 CI。

当前配置原则：

```text
pulseflow.ai.enabled = false（默认）
pulseflow.ai.mock-enabled = true
API Key 只从环境变量读取
```

因此 AI Provider 不可用时不会阻止 PulseFlow 核心业务启动。

## 9.2 四类结构化 AI Task

```text
PARSE_DSL
INSIGHT
CONTENT
REVIEW
```

所有核心 AI 输出都要求 JSON 结构化解析，不让业务代码依赖自由文本正则抽取。

## 9.3 Guardrail 组件

```text
AiFieldRegistry
CampaignDslValidator
AiOutputParser
SensitiveDataSanitizer
InsightEvidenceValidator
ContentFactValidator
ReviewEvidenceValidator
```

### 设计思想

```text
LLM 给候选答案
        ↓
Java 做最终裁决
```

这也是 PulseFlow AI 与普通“接一个大模型聊天接口”的核心区别。

---

# 十、可靠性与幂等设计总览

| 风险 | 当前机制 |
|---|---|
| Kafka 重复行为事件 | `user_event.uk_event_id` |
| MySQL 已成功、Redis 未更新 | Duplicate 后重新读取 canonical event，继续下游 |
| Redis 更新重复执行 | `event:processed:{eventId}` + Lua |
| Redis / DecisionEngine 故障 | `data_compensation_task(EVENT_REPLAY)` |
| 同一触达任务重复创建 | `delivery_task.uk_dedup` |
| Delivery Kafka 投递失败 | `dispatch_status=PENDING` + `DispatchRetryJob` |
| Delivery Kafka 重复消息 | `PENDING → PROCESSING` CAS 抢占 |
| 频控并发绕过 | Lua 原子“检查 + 占用” |
| 重试重复消耗频控 | `freq:reserved:{taskId}` |
| 站内信重复发送 | `in_app_message.uk_business_key` |
| 模拟 Push 重复发送 | `push_record.uk_business_key` |
| 定时 Campaign 多节点重复执行 | optimistic version + `campaign_execution` + CAS |
| 同一转化重复归因 | `attribution_record.uk_target_event_id` |
| AI 重复复盘 | `campaign_ai_review.uk_campaign_ai_review` + PROCESSING CAS lock |
| AI 暂时失败 | RETRYABLE_FAILED + `next_retry_at` + backoff |
| AI 空数据胡编结论 | DATA_NOT_READY / SKIPPED_INSUFFICIENT_DATA |
| AI 编造指标 | Evidence Validator |
| AI 编造优惠 | PromotionFact + ContentFactValidator |
| AI 越权查看资源 | operatorId / campaign.created_by 校验 |

---

# 十一、测试与 CI 状态

当前 `main` 已完成一次真实 GitHub Actions 全绿验收。

## 11.1 当前测试结果

```text
单元测试：98
集成测试：11
总计：109
失败：0
```

Boot 集成测试包括：

```text
FlywayMigrationIT              2
EventIdempotentConsumptionIT   3
AiModeBootstrapIT              6
--------------------------------
合计                           11
```

CI 中 Testcontainers MySQL 8.0 实际执行 V1～V5 Flyway 迁移，并验证事件幂等消费和 AI 双模式启动。

## 11.2 CI 设计

GitHub Actions 环境会强制开启 Docker 集成测试，避免出现“本地跳过 Testcontainers，CI 也不知不觉跳过”的假绿。

当前已验证完整 `mvn clean verify` BUILD SUCCESS。

---

# 十二、当前实现边界与后续优化点

这部分区分“已经实现”和“未来扩展”，避免再把计划项写成当前能力。

## 12.1 当前明确未纳入主链的能力

以下不是当前主链实现：

```text
Elasticsearch 行为全文检索
向量数据库 / RAG
多 Agent
AI 自动执行 SQL
AI 自动激活 Campaign
AI 自动发送 Campaign
在线机器学习
用户转化率预测模型
推荐算法训练平台
自动 A/B Test 优化
实时动态调价
```

## 12.2 当前 AI 数据边界

- 人群洞察 baseline 仍是 v1 候选池口径，不是真实全站 baseline；
- 部分聚合维度还未接入；
- AI Review 的质量依赖后端 summary 数据是否已归集完整；
- EMAIL 当前为 MVP 模拟通道，真实 SMTP 的端到端幂等仍需供应商能力配合。

## 12.3 当前代码值得后续继续核对的可靠性点

### A. Kafka ACK 配置一致性

当前配置为：

```text
spring.kafka.listener.ack-mode = manual
```

而业务 listener 主要通过“正常返回 / 抛异常”表达成功或失败。

后续应继续核对 Spring Kafka 实际容器确认语义，确保代码注释中的 ACK 契约与运行配置完全一致；如果坚持 manual ack，建议让 listener 显式持有并调用 `Acknowledgment`，或者统一调整为与当前异常传播方式匹配的 ack mode。

### B. Campaign 定时实例创建事务边界

当前 `CampaignSelectionJob` 已具备：

```text
version 乐观锁
campaign_execution UK
PENDING → RUNNING CAS
卡死恢复
```

但“推进 `next_trigger_at`”与“插入 `campaign_execution`”之间仍值得进一步做显式事务边界核对，防止极端情况下第一步成功、第二步异常导致某个 schedule slot 丢失。

这两项属于后续可靠性加固，不影响当前文档对主业务能力的描述，但不应该在设计文档中假装它们已经被完全解决。

---

# 十三、当前项目技术栈

```text
Java 17
Spring Boot 3.2.5
MyBatis-Plus 3.5.6
Kafka
Redis / Redisson 3.29.0
MySQL
XXL-JOB 2.4.1
Sa-Token 1.38.0
Flyway
Hutool 5.8.27
MapStruct 1.5.5.Final
Lombok 1.18.32
Testcontainers
GitHub Actions
```

AI 层：

```text
OpenAI-compatible Provider abstraction
Structured JSON output
Prompt versioning
AI audit records
Token / latency metrics
Guardrail validators
Mock Provider
```

---

# 十四、简历表述建议

## 项目名称

**PulseFlow — 实时用户行为决策引擎 / AI Campaign Copilot**

## 项目描述

基于 Spring Boot、Kafka、Redis、MySQL 与 XXL-JOB 构建事件驱动用户运营决策引擎，完成用户行为接入、三级画像计算、Campaign 规则决策、实时/延迟/定时三种触发、触达频控与幂等、点击后 Last-Touch 转化归因；在原确定性业务引擎上增加 AI Campaign Copilot，实现自然语言生成 Campaign DSL、人群洞察、营销文案和基于真实效果指标的 Campaign 复盘。

## 项目亮点

- 设计 **MySQL 事实事务 + Redis Lua 实时状态 + MySQL EVENT_REPLAY 补偿** 的分层事件处理链，重复事件从 MySQL 读取 canonical event 继续下游处理，结合 Redis processed flag 和业务唯一键实现多层幂等保护。
- 构建 **实时状态、窗口指标、长期标签三级用户画像**，通过 Kafka 事件实时更新 Redis，通过小时/日时间桶和 XXL-JOB 聚合窗口指标，并使用可扩展标签策略支持 Campaign 规则判断。
- 实现 **EVENT / DELAYED / SCHEDULED 三种 Campaign 触发模式**：Redis ZSET + Lua 管理延迟任务，统一 CampaignSelectionJob + `campaign_execution` 管理定时圈选，并通过 dedup key、乐观锁和 CAS 降低重复执行风险。
- 建立完整 **触达可靠性链路**：delivery_task 轻量 Outbox、Kafka 分发、Lua 原子频控、任务级频控 reservation、渠道 business key 幂等、WAIT_RETRY/PROCESSING 超时恢复和 DispatchRetryJob 补偿。
- 实现 **CLICK_LAST_TOUCH 转化归因**，通过归因等待任务和 Redis 宽限窗口处理迟到点击事件，并使用目标事件唯一约束防止一次转化重复归因。
- 新增 **AI Campaign Copilot**，采用“LLM 生成候选结构 + Java Guardrail 最终裁决”模式，支持自然语言→Campaign DSL、人群聚合洞察、受真实 PromotionFact 约束的营销文案和 Campaign 效果复盘；AI 只读取聚合数据，不直接执行 SQL、不绕过 Campaign、频控和触达主链。
- 为 AI Review 设计 **PENDING → PROCESSING → SUCCESS / RETRYABLE_FAILED / DATA_NOT_READY / SKIPPED / PERMANENT_FAILED** 状态机，使用条件 UPDATE 作为并发锁、指数退避控制重试，并通过 Evidence Validator 防止模型编造业务指标。
- 通过 GitHub Actions + Testcontainers 完成全量自动化回归，当前 **109 tests、0 failures**，CI 中真实执行 MySQL V1～V5 Flyway 迁移、事件幂等消费与 AI 双模式启动测试。

---

# 十五、面试时的推荐讲法

不要把 PulseFlow 讲成“我做了一个 AI 营销系统”。

更好的顺序是：

```text
1. 先讲为什么需要事件驱动
2. 再讲事件如何可靠进入 MySQL / Redis
3. 再讲三级用户画像
4. 再讲 Campaign 三种触发模式
5. 再讲触达幂等、频控和补偿
6. 再讲转化归因闭环
7. 最后才讲 AI Campaign Copilot
```

AI 的定位应该是：

```text
原系统已经可以稳定、确定性地执行 Campaign，
AI 只是把“运营人员怎么配置和理解 Campaign”这部分做得更智能。
```

这会比“接了一个大模型 API”更能体现后端系统设计能力。

---

# 十六、版本说明

本 v3.0 文档用于替换旧版 `pulseflow-design.md`。

相较旧版，主要变化：

1. 从“旧版 7 模块”更新为当前 **8 模块**，加入 `pulseflow-ai`；
2. 从旧版数据库概览更新为当前 **V1～V5、21 张物理表**；
3. XXL-JOB 从旧版 9 个更新为当前 **10 个 handler**，加入 `CampaignReviewJob`；
4. 保留并重新明确原系统 **5 条确定性业务链路**；
5. 新增完整 **4 条 AI Campaign Copilot 链路**；
6. 补齐此前漏掉的 **AI 链路 4：Campaign 效果摘要与 AI 活动复盘**；
7. 将 Elasticsearch 从“当前架构”移到未来 Stage 2 扩展，避免把规划写成已实现；
8. 增加 AI Guardrail、AI Review 状态机、AI 审计和资源 ownership；
9. 同步当前 GitHub Actions / Testcontainers **109 tests、0 failures** 的验收状态；
10. 单独列出当前仍值得继续加固的 Kafka ACK 与 Campaign 定时事务边界，避免设计文档与真实代码脱节。

---

> **维护原则**：从 v3.0 开始，本文件以 GitHub `main` 的真实代码为准。任何新能力只有在代码、迁移和测试已经落地后，才进入“当前架构”章节；未来规划统一放入“实现边界与后续优化”章节。
