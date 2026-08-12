# PulseFlow 端到端演示脚本

> 一条稳定的、可复现的演示链路：**自然语言 → Campaign DSL → 校验 → 人群预估 → 洞察 → 三条文案 → 确认创建 → 触达 → 点击与转化 → 活动复盘**。
>
> 演示原则：不现场随便输入。下面每一步都有固定输入和预期输出结构。

---

## 演示定位话术（30 秒开场）

> "这是一个事件驱动的智能用户运营后端。我先用自然语言描述一个运营目标，AI 把它翻译成受约束的规则 DSL，Java 侧做字段白名单和数字一致性校验后，由确定性规则引擎执行触达，最后 AI 只解释复盘指标——它不直接执行业务。我现在跑一遍完整链路。"

---

## 0. 环境准备

### 0.1 启动中间件

MySQL 8.0 / Redis / Kafka / XXL-JOB-Admin（默认 `http://localhost:8081/xxl-job-admin`）。

### 0.2 开启演示开关

在 `application.yml` 或环境变量中开启两个演示开关：

```yaml
pulseflow:
  ai:
    enabled: true            # 开启 AI
    mock-enabled: true       # 本地用 FakeAiModelClient，零成本、输出稳定
  dev:
    demo-login-enabled: true # 开启演示登录端点（opt-in，生产务必关闭）
```

> `mock-enabled=true` 时 AI 输出由 `FakeAiModelClient` 生成，结构稳定、不消耗 Token，最适合演示。
> `demo-login-enabled=true` 激活 `POST /api/auth/dev-login`，用于获取 Sa-Token。

### 0.3 启动应用

```bash
cd pulseflow
mvn clean install -DskipTests
mvn spring-boot:run -pl pulseflow-boot
# 应用监听 8080
```

### 0.4 灌入演示种子数据

```bash
mysql -u root -p pulseflow < docs/demo/seed-demo-data.sql
```

种子数据写入 5 个用户：3 个命中规则（1024/1025/1026），2 个对照（1027 活跃不足、1028 消费不足）。**人群预估应稳定返回 3。**

---

## 1. 演示登录（获取 Token）

```bash
curl -s -X POST "http://localhost:8080/api/auth/dev-login?operatorId=1024"
```

**预期响应：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "operatorId": 1024,
    "tokenName": "token",
    "tokenValue": "xxxx-xxxx-xxxx",
    "loginId": "1024"
  }
}
```

保存 `tokenValue`，后续所有请求带 `token: <tokenValue>` 头：

```bash
export TOKEN="xxxx-xxxx-xxxx"
```

> **讲点**：`operatorId` 来自 Sa-Token 登录态（`StpUtil.getLoginId()`），请求体里的 `operatorId` 字段被服务端忽略——防伪造，每次 AI 操作都可追溯。

---

## 2. 自然语言 → Campaign DSL（AI 理解层）

### 固定输入（演示话术）

> 筛选最近 7 天活跃不少于 5 天、最近 30 天消费超过 500 元、最近 3 天未购买的用户，今晚 8 点发送满 300 减 30 的站内信，每 24 小时最多触达一次。

```bash
curl -s -X POST "http://localhost:8080/api/ai/campaigns/parse" \
  -H "Content-Type: application/json" \
  -H "token: $TOKEN" \
  -d '{
    "text": "筛选最近7天活跃不少于5天、最近30天消费超过500元、最近3天未购买的用户，今晚8点发送满300减30的站内信，每24小时最多触达一次。",
    "timezone": "Asia/Shanghai"
  }'
```

### 预期稳定的 DSL（VALIDATED）

AI 产出的 DSL 必须落在下面的结构上（字段全部来自 `AiFieldRegistry` 白名单）：

```json
{
  "schemaVersion": 1,
  "campaignName": "近30天高消费未复购用户满减挽回",
  "objective": "CONVERSION",
  "audience": {
    "logic": "AND",
    "conditions": [
      {"field": "activeDays7d",          "operator": "GTE", "valueType": "INTEGER", "value": 5},
      {"field": "spend30d",              "operator": "GT",  "valueType": "DECIMAL", "value": 500},
      {"field": "daysSinceLastPurchase", "operator": "GTE", "valueType": "INTEGER", "value": 3}
    ]
  },
  "channel": "IN_APP",
  "schedule": {
    "type": "ONCE",
    "sendAt": "<now+1h, FakeAiModelClient 动态生成>",
    "timezone": "Asia/Shanghai"
  },
  "frequencyCap": {"maxTimes": 1, "windowHours": 24},
  "promotionFacts": [
    {"type": "FULL_REDUCTION", "threshold": 300, "discount": 30, "description": "满300减30"}
  ]
}
```

**响应关键字段：**
- `status`: `VALIDATED`（promotionFacts 齐全 → 可直接确认）
- `draftId`: 后续步骤用
- `estimatedAudience.count`: **3**（命中 1024/1025/1026）
- `dsl`: 上面的结构

> **讲点（AI 安全边界）**：
> - 三个条件字段 `activeDays7d` / `spend30d` / `daysSinceLastPurchase` 必须在白名单内，否则 422。
> - `activeDays7d` 值不能超过 7（业务边界强校验）。
> - `sendAt` 必须是未来时间 + 带 offset + 与 timezone 交叉校验。
> - AI 只生成 DSL，**不创建 Campaign**——确认创建在第 5 步由人触发。

---

## 3. 人群洞察（AI 解释 Java 算好的指标）

```bash
curl -s -X POST "http://localhost:8080/api/ai/campaigns/drafts/$DRAFT_ID/insight" \
  -H "token: $TOKEN"
```

**预期响应结构：**

```json
{
  "code": 200,
  "data": {
    "requestId": "...",
    "draftId": <DRAFT_ID>,
    "metrics": {
      "estimatedAudienceCount": 3,
      "avgSpend30d": 1033.17,
      "activeDays7dAvg": 6.0,
      "highValueRatio": 0.67
    },
    "insight": "目标人群 3 人，近30天平均消费 ¥1033.17，高价值用户占比 67%……",
    "dataQuality": {
      "baselineType": "CANDIDATE_POOL",
      "proxyMetrics": [],
      "unavailableMetrics": []
    }
  }
}
```

> **讲点**：
> - `metrics` 由 Java 聚合计算，AI 只写 `insight` 文字解释。
> - `evidenceKeys` 校验：AI 文字里提到的数字必须等于 `metrics` 里的数字，对不上返回 422——防幻觉。
> - `dataQuality` 把口径限制同步给前端："当前对比基线来自候选用户池，并非全站数据。"

---

## 4. 三条文案（优惠事实服务端权威）

```bash
curl -s -X POST "http://localhost:8080/api/ai/campaigns/drafts/$DRAFT_ID/contents" \
  -H "Content-Type: application/json" \
  -H "token: $TOKEN" \
  -d '{"tone": "WARM", "variantCount": 3}'
```

**预期响应结构（3 条差异化文案）：**

```json
{
  "code": 200,
  "data": {
    "requestId": "...",
    "draftId": <DRAFT_ID>,
    "content": [
      {"variant": "A", "title": "您有一份满减待领取", "body": "满300立减30元……"},
      {"variant": "B", "title": "精选好物 限时优惠",   "body": "满300减30……"},
      {"variant": "C", "title": "老用户专属福利",     "body": "满300减30……"}
    ]
  }
}
```

> **讲点**：优惠金额（满300减30）只从服务端草稿的 `promotionFacts` 读取，请求体不能覆盖。`ContentFactValidator` 会丢弃任何提到草稿中不存在的数字的文案——AI 改不了优惠力度。

---

## 5. 确认创建（AI 不绕过，走原 Campaign 引擎）

```bash
curl -s -X POST "http://localhost:8080/api/campaigns/from-ai-draft/$DRAFT_ID" \
  -H "token: $TOKEN"
```

**预期响应：**

```json
{
  "code": 200,
  "data": {
    "campaignId": 1,
    "draftId": <DRAFT_ID>,
    "idempotent": false
  }
}
```

> **讲点**：
> - 这是 AI → 业务库的唯一入口，由 `CampaignAiDraftService.confirmAndCreate` 写真实 `campaign` + `campaign_rule`，并写入 `created_by=1024`（资源归属）。
> - 重复确认幂等（`idempotent=true`，只创建一次 Campaign）。
> - AI 全程没碰业务库——它只产出草稿，确认由人触发，执行由 Java 规则引擎。

保存 `campaignId`：

```bash
export CAMPAIGN_ID=1
```

---

## 6. 触达（确定性规则引擎执行）

演示 Campaign 是 `SCHEDULED + ONCE` 类型，由 `CampaignSelectionJob` 在 `sendAt` 到点时批量圈人。

### 6.1 触发执行

两种方式：
- **等到 8 点**：Job 自动圈选 1024/1025/1026，创建 `DeliveryTask`。
- **立即演示**：在 XXL-JOB-Admin（`http://localhost:8081/xxl-job-admin`）手动执行 `CampaignSelectionJob`。

### 6.2 验证触达任务

```sql
SELECT id, campaign_id, user_id, channel, status, dedup_key
FROM delivery_task
WHERE campaign_id = 1
ORDER BY user_id;
```

**预期：3 条 SENT 任务**（1024/1025/1026 各一条），`dedup_key` 唯一。

> **讲点（频控）**：`FrequencyControlService` 用 Lua 脚本原子完成"用户日限 + 活动周限 + 重试幂等"。`frequencyCap.maxTimes=1, windowHours=24` → 每用户 24 小时内最多触达 1 次。重复执行 Job 不会重复触达（`dedup_key` 唯一索引 + `freq:reserved:{taskId}`）。

---

## 7. 点击与转化 → Last-touch 归因

### 7.1 模拟用户点击站内信

```bash
# 假设 delivery_task.id = 1，user_id = 1024
curl -s -X POST "http://localhost:8080/api/events" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt_click_demo_001",
    "userId": 1024,
    "eventType": "CAMPAIGN_CLICK",
    "targetId": 1,
        "eventTime": "<当前时间，按演示时调整>",,
    "properties": {"taskId": 1, "campaignId": 1}
  }'
```

### 7.2 模拟转化（下单）

```bash
curl -s -X POST "http://localhost:8080/api/events" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt_order_demo_001",
    "userId": 1024,
    "eventType": "ORDER_PAID",
    "targetId": 9999,
        "eventTime": "<当前时间，按演示时调整>",,
    "properties": {"orderId": "ord_demo_001", "price": 328.00}
  }'
```

### 7.3 验证归因

```sql
SELECT target_event_id, user_id, campaign_id, task_id, attribution_model, attribution_window_hours
FROM attribution_record
WHERE user_id = 1024;
```

**预期：** 一条 `CLICK_LAST_TOUCH` 归因记录，`campaign_id=1`，`attribution_window_hours=24`（点击后 25 分钟转化，在 24h 窗口内）。

> **讲点**：转化事件进 `AttributionService`，向前回溯 24h 窗口内的最近一次点击，匹配到 Campaign，写 `attribution_record`。超过 24h 窗口的点击 `EXPIRED`。归因同样用 `DuplicateKeyException` 幂等。

---

## 8. 活动复盘（AI 解释，指标 Java 先算）

### 8.1 触发复盘

复盘由 `CampaignReviewJob`（XXL-JOB）扫描已结束的 Campaign。演示时手动触发，或直接调用 regenerate：

```bash
curl -s -X POST "http://localhost:8080/api/ai/campaigns/$CAMPAIGN_ID/review/regenerate" \
  -H "token: $TOKEN"
```

### 8.2 预期响应结构

```json
{
  "code": 200,
  "data": {
    "campaignId": 1,
    "status": "SUCCESS",
    "model": "fake-mock-model",
    "promptVersion": "v1",
    "review": {
      "performanceSummary": {
        "sentCount": 3,
        "clickCount": 1,
        "conversionCount": 1,
        "ctr": 0.3333,
        "cvr": 1.0,
        "spend": 0,
        "roI": null
      },
      "conclusion": "本次活动触达 3 人，点击 1 人，转化 1 人，CTR 33.33%……",
      "evidenceKeys": ["sentCount=3", "clickCount=1", "conversionCount=1"]
    }
  }
}
```

> **讲点（可靠性核心）**：
> - `CampaignReviewJob` 扫描时用 `campaign_ai_review` 表 CAS 抢占：`UPDATE ... SET status='PROCESSING' WHERE status IN ('PENDING',...)`，只有 `affected=1` 的执行器调 LLM，防并发重复调用。
> - `PerformanceSummaryCalculator.compute()` 在 AI 调用前算好并持久化——**AI 失败不丢指标**。
> - `evidenceKeys` + 数字一致性校验：AI 结论里的数字必须等于 Java 算的数字，对不上 422。
> - 数据未就绪三态：`sentCount=0` 宽限期内 `DATA_NOT_READY`（可重试），过宽限期 `SKIPPED_INSUFFICIENT_DATA`（终态），避免把"消费链路延迟"误判成"数据不足"。
> - 60 秒冷却防 regenerate 烧钱（429）。
> - `requireCampaignOwner`：只有 `created_by=1024` 的创建者本人能看复盘，历史 `null` 数据默认拒绝——防越权。

---

## 9. AI 关闭演示（双模式）

把 `pulseflow.ai.enabled` 改回 `false` 重启：

- 应用正常启动，所有 AI Bean 缺失
- `/api/ai/**` 返回 503 `AiDisabledException`
- 原 Campaign 链路（事件 → 画像 → 决策 → 触达 → 归因）不受影响

> **讲点**：AI 是叠加层，可彻底关闭，不阻塞主链路。

---

## 演示节奏建议（8 分钟版）

| 分钟 | 步骤 | 重点话术 |
|---|---|---|
| 0-1 | 开场 + 架构图 | 事件链路 + AI 只理解不执行 |
| 1-2 | 登录 + 灌种子 | operatorId 服务端权威 |
| 2-3 | NL → DSL | 字段白名单 + 校验截断 |
| 3-4 | 人群预估 = 3 | Java 聚合，AI 不编数字 |
| 4-5 | 洞察 + 文案 | evidenceKeys + 优惠服务端权威 |
| 5-6 | 确认创建 | AI 不绕过，写 created_by |
| 6-7 | 触达 + 频控 | Lua 原子频控 + dedup 幂等 |
| 7-8 | 归因 + 复盘 | CAS 状态机 + AI 失败不丢指标 |

---

## 故障排查

| 现象 | 原因 | 解决 |
|---|---|---|
| `/api/**` 全 401 | 未带 `token` 头或 token 过期 | 重新调 `/api/auth/dev-login` |
| `/api/auth/dev-login` 404 | `demo-login-enabled` 未开 | 设 `pulseflow.dev.demo-login-enabled=true` |
| 人群预估 = 0 | 种子数据未灌入或用户 status≠1 | 执行 `seed-demo-data.sql`，检查 `user_profile.status` |
| parse 返回 NEEDS_CONFIRMATION | mock 模式下 AI 未填 promotionFacts | 用 `PUT /drafts/{id}` 补 promotionFacts 后再确认 |
| 复盘 403 | 登录用户 ≠ campaign.created_by | 用 `operatorId=1024` 登录（种子数据创建者） |
| 复盘 429 | 60s 冷却内重复 regenerate | 等 60s 或换 campaignId |
