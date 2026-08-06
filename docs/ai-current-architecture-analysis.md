# PulseFlow AI Campaign Copilot — 现状架构分析

> 阶段0 输出。本文档基于对 `pulseflow/` 当前代码的真实扫描，列出 AI 改造可复用、需新增、需修改的内容，并给出实施顺序。

---

## 1. 项目技术栈与版本

| 项 | 值 |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| MyBatis-Plus | 3.5.6 |
| Redisson | 3.29.0 |
| Kafka | spring-kafka（Spring Boot 管理） |
| XXL-JOB | 2.4.1 |
| Sa-Token | 1.38.0 |
| Flyway | 10.12.0（flyway-mysql） |
| Hutool | 5.8.27 |
| Lombok / MapStruct | 1.18.32 / 1.5.5.Final |
| 测试 | spring-boot-starter-test + Testcontainers(mysql/kafka/junit-jupiter) |
| 包路径根 | `com.pulseflow`（注意：实体与 mapper 在 `com.pulseflow.entity` / `com.pulseflow.mapper`，**不在** `common` 子包内） |

---

## 2. 模块结构（实际）

```
pulseflow/                          父 POM, packaging=pom
├── pulseflow-common                实体 / Mapper / 枚举 / DTO / 工具 / 异常 / ApiResponse
├── pulseflow-event                 事件接入 REST + Kafka 三阶段消费
├── pulseflow-profile               ProfileService + UserPreferenceService + tag 策略
├── pulseflow-campaign              DecisionEngine / DelayedTaskManager / Delivery / FrequencyControl / Attribution
├── pulseflow-job                   9 个 XXL-JOB handler
├── pulseflow-simulator             SimulatorController + thymeleaf 页面
└── pulseflow-boot                  启动类 + 配置聚合 + Flyway 迁移脚本
```

新增模块：`pulseflow-ai`

---

## 3. 可复用的现有类

### 3.1 实体（位于 `com.pulseflow.entity`）

| 类 | 用途 | AI 改造复用点 |
|---|---|---|
| `Campaign` | 活动 | AI 草稿确认后插入此表 |
| `CampaignRule` | 规则定义（rule_type + rule_config JSON） | DSL 转换目标 |
| `CampaignExecution` | 活动执行实例 | 复盘关联 |
| `DeliveryTask` / `DeliveryRecord` | 触达任务/记录 | 复盘指标聚合来源 |
| `ClickEvent` | 点击事件 | 复盘点击率 |
| `AttributionRecord` | 归因记录 | 复盘转化率 |
| `UserProfile` | 用户基础信息 | 人群预估候选源 |
| `UserBehaviorSummary` | 窗口指标（search_1h / active_7d / spend_30d / fav_7d） | AI 字段注册中心 + 人群聚合 |
| `UserMetricDaily` / `UserMetricHourly` | 日报/小时报桶 | 人群洞察聚合计算 |
| `UserTag` | 用户标签（HIGH_VALUE/CHURN_RISK/PRICE_SENSITIVE/AI_PREF 等） | AI 字段注册中心 + 人群聚合 |

### 3.2 枚举（位于 `com.pulseflow.common.enums`）

| 枚举 | 值 | 复用点 |
|---|---|---|
| `CampaignStatus` | DRAFT/ACTIVE/PAUSED/CLOSED | AI 草稿确认后置为 DRAFT，由运营激活 |
| `ChannelType` | IN_APP/PUSH/EMAIL | DSL channel 字段白名单 |
| `TriggerType` | EVENT/DELAYED/SCHEDULED | DSL schedule.type → triggerType 映射 |
| `RuleType` | PROFILE/FREQUENCY/EVENT | DSL 转换后的 campaign_rule.rule_type |
| `CampaignStatus` / `ExecutionStatus` / `TaskStatus` / `DispatchStatus` / `AttributionTaskStatus` | — | 状态机沿用 |

### 3.3 服务（位于各业务模块）

| 类 | 路径 | 复用点 |
|---|---|---|
| `ProfileService` | `pulseflow-profile/.../ProfileService.java` | `hasTag` / `getMetricValue` / `getWindowMetrics` / `getUserTags` — 人群洞察与 DSL 转换的基础 |
| `UserPreferenceService` | `pulseflow-profile/.../UserPreferenceService.java` | 频控前置（canDeliver） |
| `DecisionEngine` | `pulseflow-campaign/.../DecisionEngine.java` | **AI 草稿确认后必须经此入口**，`evaluateBatch` 走 SCHEDULED 路径 |
| `DeliveryService` | `pulseflow-campaign/.../DeliveryService.java` | 创建 delivery_task（保留 dedup_key 幂等） |
| `FrequencyControlService` | `pulseflow-campaign/.../FrequencyControlService.java` | DSL 频控字段转 campaign.user_daily_limit / campaign_weekly_limit |
| `AttributionService` | `pulseflow-campaign/.../AttributionService.java` | 复盘转化指标来源 |
| `ClickEventService` | `pulseflow-campaign/.../ClickEventService.java` | 复盘点击指标来源 |

### 3.4 Mapper（位于 `com.pulseflow.mapper`）

全部继承 `BaseMapper<T>`，AI 模块需要新增 4 个 Mapper：`CampaignAiDraftMapper` / `AiGenerationRecordMapper` / `CampaignPerformanceSummaryMapper` / `CampaignAiReviewMapper`。

### 3.5 工具与基础设施

| 类 | 路径 | 复用点 |
|---|---|---|
| `JsonUtil` | `pulseflow-common/.../JsonUtil.java` | Jackson + jsr310，AI 输出解析复用 |
| `ApiResponse` | `pulseflow-common/.../ApiResponse.java` | 统一响应包装 |
| `PulseFlowException` | `pulseflow-common/.../PulseFlowException.java` | AI 异常基类（带 errorCode） |
| `GlobalExceptionHandler` | `pulseflow-boot/.../config/GlobalExceptionHandler.java` | 新增 AI 异常 handler |
| `MyMetaObjectHandler` | `pulseflow-common/.../config/MyMetaObjectHandler.java` | created_at / updated_at 自动填充 |
| `DedupKeyUtil` | `pulseflow-common/.../util/DedupKeyUtil.java` | AI 草稿确认调用 DecisionEngine 时的 dedup key 生成 |

### 3.6 XXL-JOB

`pulseflow-job/.../handler/` 下 9 个 handler 全部使用 `@XxlJob("name")` 注解注册，新增 `CampaignReviewJob` 沿用此模式。`@Component` 自动被 Spring 扫描，无需额外注册。

### 3.7 Flyway 迁移

`pulseflow-boot/src/main/resources/db/migration/V1__init.sql`、`V2__channel_tables.sql`，新增 `V3__ai_campaign_tables.sql` 沿用此规范。

### 3.8 配置

`pulseflow-boot/src/main/resources/application.yml` 已配 `spring.flyway` / `mybatis-plus` / `xxl.job` / `sa-token`。新增 `pulseflow.ai.*` 配置段。

---

## 4. 需要新增的类

### 4.1 pulseflow-ai 模块（全新）

```
pulseflow-ai/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/pulseflow/ai/
    │   │   ├── api/
    │   │   │   ├── AiCampaignController.java          # /api/ai/campaigns/parse, /drafts/{id}, /drafts/{id}/insight, /drafts/{id}/contents
    │   │   │   ├── AiReviewController.java            # /api/ai/campaigns/{campaignId}/review[/regenerate]
    │   │   │   └── dto/                               # 请求/响应 DTO
    │   │   ├── application/
    │   │   │   ├── CampaignIntentService.java         # 自然语言 → DSL
    │   │   │   ├── CampaignAiDraftService.java        # 草稿 CRUD + 校验 + 确认
    │   │   │   ├── AudienceInsightService.java        # 聚合指标 + AI 洞察
    │   │   │   ├── CampaignContentService.java        # 文案生成
    │   │   │   ├── CampaignReviewService.java         # 复盘生成
    │   │   │   └── AudiencePreviewService.java        # 人群预估
    │   │   ├── domain/
    │   │   │   ├── campaign/
    │   │   │   │   ├── CampaignDsl.java               # DSL 根对象
    │   │   │   │   ├── AudienceCondition.java
    │   │   │   │   ├── AudienceGroup.java
    │   │   │   │   ├── CampaignSchedule.java
    │   │   │   │   ├── FrequencyCap.java
    │   │   │   │   ├── PromotionFact.java
    │   │   │   │   ├── DslValidationResult.java
    │   │   │   │   └── DraftStatus.java               # 枚举
    │   │   │   ├── insight/
    │   │   │   │   ├── AudienceMetrics.java
    │   │   │   │   ├── InsightResult.java
    │   │   │   │   ├── Finding.java
    │   │   │   │   └── StrategySuggestion.java
    │   │   │   ├── content/
    │   │   │   │   ├── ContentRequest.java
    │   │   │   │   ├── ContentVariant.java
    │   │   │   │   ├── ContentResult.java
    │   │   │   │   └── ContentVariantType.java         # DIRECT_BENEFIT/URGENCY/PERSONALIZED
    │   │   │   └── review/
    │   │   │       ├── PerformanceSummary.java
    │   │   │       ├── ReviewResult.java
    │   │   │       ├── ReviewHighlight.java
    │   │   │       ├── ReviewProblem.java
    │   │   │       └── NextAction.java
    │   │   ├── provider/
    │   │   │   ├── AiModelClient.java                  # 接口
    │   │   │   ├── AiRequest.java
    │   │   │   ├── AiResponse.java
    │   │   │   ├── OpenAiCompatibleClient.java         # OpenAI 协议（DeepSeek 等）
    │   │   │   └── FakeAiModelClient.java              # Mock，可按 taskType 返回固定 JSON
    │   │   ├── prompt/
    │   │   │   ├── CampaignIntentPromptBuilder.java
    │   │   │   ├── AudienceInsightPromptBuilder.java
    │   │   │   ├── CampaignContentPromptBuilder.java
    │   │   │   ├── CampaignReviewPromptBuilder.java
    │   │   │   └── PromptVersion.java
    │   │   ├── guardrail/
    │   │   │   ├── AiFieldRegistry.java                # 字段白名单
    │   │   │   ├── CampaignDslValidator.java           # DSL 全量校验
    │   │   │   ├── AiOutputParser.java                 # JSON 提取 + 解析
    │   │   │   ├── ContentFactValidator.java           # 文案事实校验
    │   │   │   ├── InsightEvidenceValidator.java       # evidenceKeys + 数字一致性校验
    │   │   │   └── SensitiveDataSanitizer.java         # 输入脱敏
    │   │   ├── infrastructure/
    │   │   │   ├── config/
    │   │   │   │   ├── AiAutoConfiguration.java        # 按 enabled/mock-enabled 装配 Client
    │   │   │   │   └── AiFeatureProperties.java        # @ConfigurationProperties("pulseflow.ai")
    │   │   │   ├── persistence/
    │   │   │   │   ├── entity/
    │   │   │   │   │   ├── CampaignAiDraft.java
    │   │   │   │   │   ├── AiGenerationRecord.java
    │   │   │   │   │   ├── CampaignPerformanceSummary.java
    │   │   │   │   │   └── CampaignAiReview.java
    │   │   │   │   └── mapper/
    │   │   │   │       ├── CampaignAiDraftMapper.java
    │   │   │   │       ├── AiGenerationRecordMapper.java
    │   │   │   │       ├── CampaignPerformanceSummaryMapper.java
    │   │   │   │       └── CampaignAiReviewMapper.java
    │   │   │   └── observability/
    │   │   │       ├── AiAuditService.java             # 写 ai_generation_record
    │   │   │       └── AiMetrics.java                  # Micrometer（项目已 actuator）
    │   │   └── support/
    │   │       ├── AiTaskType.java                     # PARSE_DSL/INSIGHT/CONTENT/REVIEW
    │   │       ├── AiErrorCode.java                    # AI_DISABLED/AI_INVALID_JSON/...
    │   │       └── AiExceptions.java                   # AiDisabledException / AiProviderException / AiOutputInvalidException
    │   └── resources/prompts/
    │       ├── campaign-intent-v1.md
    │       ├── audience-insight-v1.md
    │       ├── campaign-content-v1.md
    │       └── campaign-review-v1.md
    └── test/java/com/pulseflow/ai/
        ├── guardrail/
        │   ├── CampaignDslValidatorTest.java
        │   ├── AiOutputParserTest.java
        │   ├── ContentFactValidatorTest.java
        │   └── InsightEvidenceValidatorTest.java
        ├── provider/
        │   └── FakeAiModelClientTest.java
        ├── application/
        │   ├── CampaignIntentServiceTest.java
        │   ├── AudienceInsightServiceTest.java
        │   ├── CampaignContentServiceTest.java
        │   └── CampaignReviewServiceTest.java
        └── integration/
            └── AiCampaignE2EIT.java                     # Testcontainers MySQL + FakeAiModelClient
```

### 4.2 新增 Mapper（位于 `com.pulseflow.mapper` 之外的 AI 模块内部）

> MapperScan 当前为 `com.pulseflow.mapper`，需扩展为 `com.pulseflow.mapper,com.pulseflow.ai.infrastructure.persistence.mapper`，或在 AI Mapper 上加 `@Mapper`。**采用扩展 MapperScan 路径**（最少侵入）。

### 4.3 新增数据库表（V3 迁移）

- `campaign_ai_draft`
- `ai_generation_record`
- `campaign_performance_summary`
- `campaign_ai_review`

详见执行方案 §12。MySQL JSON 类型在当前 Flyway/MyBatis-Plus 下可用，**保留 JSON 类型**（V1 已使用 JSON 列）。

### 4.4 新增 XXL-JOB

- `CampaignReviewJob`：扫描已结束 N 小时但未生成 performance_summary 的 campaign，先汇总后调用 AI 复盘。

### 4.5 新增 API（Campaign 业务入口）

执行方案 §11.5 要求 `POST /api/campaigns/from-ai-draft/{draftId}` 属于 Campaign 业务入口。新增 `CampaignFromAiDraftController` 位于 `pulseflow-campaign`，调用 `CampaignAiDraftService.confirmAndCreate`（该方法返回 campaignId 后由原 DecisionEngine 路径执行）。

---

## 5. 需要修改的类

| 文件 | 修改内容 |
|---|---|
| `pulseflow/pom.xml` | `<modules>` 增加 `pulseflow-ai`；`dependencyManagement` 增加 pulseflow-ai |
| `pulseflow-boot/pom.xml` | 增加 pulseflow-ai 依赖 |
| `pulseflow-boot/.../PulseFlowApplication.java` | `@MapperScan` 扩展为 `{"com.pulseflow.mapper","com.pulseflow.ai.infrastructure.persistence.mapper"}` |
| `pulseflow-boot/.../config/GlobalExceptionHandler.java` | 新增 `@ExceptionHandler(AiException)` 返回 AI 错误码 |
| `pulseflow-boot/src/main/resources/application.yml` | 新增 `pulseflow.ai.*` 配置段 |
| `pulseflow-job/pom.xml` | 增加 pulseflow-ai 依赖（CampaignReviewJob 需要） |
| `pulseflow-boot/src/main/resources/db/migration/` | 新增 `V3__ai_campaign_tables.sql` |

**禁止修改**：
- `DecisionEngine` / `DeliveryService` / `FrequencyControlService` / `AttributionService` 等核心业务类（AI 只调用，不修改）
- Kafka topic 与消息格式
- 现有 Flyway 已应用的 V1/V2
- 用户画像计算口径

---

## 6. 模块依赖图

```
                      pulseflow-boot
                       │
        ┌──────────────┼───────────────┬─────────────┐
        ▼              ▼               ▼             ▼
  pulseflow-ai   pulseflow-job   pulseflow-event  pulseflow-simulator
        │              │
        │              │
        ▼              ▼
  pulseflow-campaign ◄─ (反向依赖禁止)
        │
        ▼
  pulseflow-profile
        │
        ▼
  pulseflow-common
```

`pulseflow-ai` 依赖：
- `pulseflow-common`（实体/Mapper/工具/异常）
- `pulseflow-profile`（ProfileService 查询接口）
- `pulseflow-campaign`（DecisionEngine/DeliveryService/AttributionService — 仅 application 层调用，不反向被依赖）

`pulseflow-job` 增加 `pulseflow-ai` 依赖以承载 `CampaignReviewJob`。

---

## 7. 数据流图

### 7.1 AI 创建 Campaign 主链路

```
运营人员
  │ POST /api/ai/campaigns/parse {text, timezone}
  ▼
AiCampaignController
  ▼
CampaignIntentService
  ├─ SensitiveDataSanitizer 检查输入
  ├─ CampaignIntentPromptBuilder 构建 Prompt（注入 AiFieldRegistry 字段清单）
  ├─ AiModelClient.generateStructured (Fake 或 OpenAI Compatible)
  ├─ AiAuditService 记录 ai_generation_record
  ├─ AiOutputParser 提取 JSON（处理 markdown 包裹）
  ├─ CampaignDslValidator 全量校验
  │     ├─ JSON 解析
  │     ├─ 必填字段
  │     ├─ 未知字段
  │     ├─ 字段类型 / 操作符 / 数值范围 / 枚举
  │     ├─ 时间 / 频控 / 规则复杂度
  │     └─ 字段必须存在于 AiFieldRegistry
  ├─ AudiencePreviewService.preview(dsl → AudienceRule)
  │     └─ 复用 ProfileService + UserProfileMapper 全表扫描估算（与 CampaignSelectionJob 同口径）
  └─ CampaignAiDraftService.saveDraft(status=VALIDATED 或 NEEDS_CONFIRMATION)
  ▼
返回 {requestId, draftId, status, dsl, estimatedAudience, missingFields, warnings}
```

### 7.2 草稿确认创建

```
运营人员
  │ POST /api/campaigns/from-ai-draft/{draftId}
  ▼
CampaignFromAiDraftController（pulseflow-campaign）
  ▼
CampaignAiDraftService.confirmAndCreate
  ├─ 检查草稿状态 = VALIDATED
  ├─ 检查未过期（expires_at）
  ├─ 重新执行 CampaignDslValidator（防止草稿期间字段注册中心变更）
  ├─ 必要时重新预估（profile_data_version 过期）
  ├─ DSL → Campaign + CampaignRule（rule_type=PROFILE, rule_config=JSON）
  ├─ INSERT campaign（status=DRAFT, trigger_type=SCHEDULED, next_trigger_at=sendAt）
  ├─ INSERT campaign_rule
  ├─ UPDATE campaign_ai_draft.confirmed_campaign_id / confirmed_at / status=CONFIRMED
  └─ 返回 campaignId
（此后由运营在管理后台激活，CampaignSelectionJob 接管执行）
```

### 7.3 人群洞察

```
GET /api/ai/campaigns/drafts/{draftId}/insight
  ▼
AudienceInsightService
  ├─ 加载草稿 DSL
  ├─ AudiencePreviewService.preview 获取人群 userId 列表（带分页上限）
  ├─ 批量读 ProfileService.getWindowMetrics + getUserTags
  ├─ 后端聚合：activeRate7d / averageSpend30d / cartWithoutPurchaseRate / highValueRate / ...（聚合而非明细）
  ├─ 全站基线： UserProfile 全量随机采样或近 30d 聚合
  ├─ SensitiveDataSanitizer：聚合后才送 AI，禁止 userId/手机号
  ├─ AudienceInsightPromptBuilder 构建 Prompt
  ├─ AiModelClient.generateStructured
  ├─ AiAuditService 记录
  ├─ AiOutputParser 解析
  └─ InsightEvidenceValidator 校验 evidenceKeys + 数字一致性
  ▼
返回 {metrics, insight:{summary, findings, strategySuggestions, risks}}
```

### 7.4 文案生成

```
POST /api/ai/campaigns/drafts/{draftId}/contents {tone, variantCount}
  ▼
CampaignContentService
  ├─ 加载草稿 DSL（promotionFacts 必须存在，否则 AI_MISSING_REQUIRED_FACT）
  ├─ 构造 ContentRequest（promotionFacts 来自草稿，不允许前端覆盖）
  ├─ CampaignContentPromptBuilder
  ├─ AiModelClient.generateStructured
  ├─ AiAuditService
  ├─ AiOutputParser
  └─ ContentFactValidator
        ├─ 标题/正文长度
        ├─ 禁止词
        ├─ 未替换模板变量
        ├─ 优惠数字必须来自 promotionFacts
        ├─ 不得修改门槛
        ├─ 不得捏造期限/库存
        └─ 不得包含敏感字段
  ▼
返回 {variants:[{type,title,body,strategy}]}
```

### 7.5 活动复盘

```
XXL-JOB: CampaignReviewJob（每小时）
  ▼
扫描 status=CLOSED 且 finished_at < now-2h 且无 campaign_performance_summary 的活动
  ▼
CampaignReviewService.computePerformance(campaignId)
  ├─ SELECT COUNT(*) FROM delivery_task WHERE campaign_id=?
  ├─ SELECT COUNT(*) FROM delivery_record WHERE campaign_id=? AND status='SENT'
  ├─ SELECT COUNT(*) FROM click_event ce JOIN delivery_record dr ON ce.task_id=dr.task_id WHERE dr.campaign_id=?
  ├─ SELECT COUNT(*) FROM attribution_record WHERE campaign_id=?
  ├─ SELECT COUNT(*) FROM user_preference_unsubscribe（如不存在则 unsubscribeCount=0）
  ├─ 计算 deliveryRate/clickRate/conversionRate/unsubscribeRate
  ├─ 历史基线：近 30 天同类活动平均值
  ├─ contentVariants：按 message_content 分组（如有 metadata.variant 字段）
  └─ INSERT campaign_performance_summary (UK campaign_id)
  ▼
CampaignReviewService.generateReview(campaignId)
  ├─ 加载 PerformanceSummary
  ├─ CampaignReviewPromptBuilder
  ├─ AiModelClient.generateStructured
  ├─ AiAuditService
  ├─ AiOutputParser
  ├─ InsightEvidenceValidator 校验
  └─ UPSERT campaign_ai_review (UK campaign_id)
  ▼
失败：campaign_ai_review.status=FAILED，保留 performance_summary，允许 /regenerate 重试

GET /api/ai/campaigns/{campaignId}/review
  ▼
返回 performance_summary + ai_review
```

---

## 8. AI 字段注册中心映射（基于现有数据）

| fieldCode | valueType | sourceType | 现有数据来源 |
|---|---|---|---|
| todayViews | INTEGER | REALTIME_PROFILE | `user:daily:{userId}:{yyyyMMdd}` view 计数 |
| cartItemCount | INTEGER | REALTIME_PROFILE | `user:cart:{userId}` size |
| searchCount1h | INTEGER | WINDOW_PROFILE | `user_behavior_summary` metric_type=`search_1h` |
| activeDays7d | INTEGER | WINDOW_PROFILE | 由 `active_7d`（CONTENT_VIEW 计数）派生 |
| viewCount7d | INTEGER | WINDOW_PROFILE | `user_behavior_summary` 近 7d CONTENT_VIEW |
| spend30d | DECIMAL | WINDOW_PROFILE | `user_behavior_summary` metric_type=`spend_30d` |
| orderCount30d | INTEGER | WINDOW_PROFILE | `user_metric_daily` 近 30d ORDER_PAID 计数 |
| daysSinceLastPurchase | INTEGER | WINDOW_PROFILE | max(effective_event_time of ORDER_PAID) 派生 |
| registrationDays | INTEGER | USER_PROFILE | `user_profile.created_at` 派生 |
| memberLevel | STRING | USER_PROFILE | （现有 user_profile 无此字段，标 disabled 或从 user_tag 派生） |
| preferredCategory | STRING | USER_PROFILE | （现有数据无，标 disabled） |
| HIGH_VALUE | BOOLEAN | TAG | `user_tag.tag_name=HIGH_VALUE` |
| PRICE_SENSITIVE | BOOLEAN | TAG | `user_tag.tag_name=PRICE_SENSITIVE` |
| CHURN_RISK | BOOLEAN | TAG | `user_tag.tag_name=CHURN_RISK` |

注：`memberLevel` / `preferredCategory` 现有数据库无字段，第一版标 `enabled=false`，避免 AI 引用不存在的字段。

---

## 9. DSL → Campaign/CampaignRule 转换规则

| DSL 字段 | 目标 |
|---|---|
| `campaignName` | `campaign.name` |
| `objective` | `campaign.description` 前缀 `[OBJ:CONVERSION]`（现有 Campaign 无 objective 字段，避免改表） |
| `audience.conditions` | 多条 `campaign_rule`（rule_type=PROFILE，rule_config={"metricType":"...","operator":"...","threshold":...}）或标签规则（{"tagName":"...","operator":"EQ","value":"1"}） |
| `audience.logic` | 多条规则 AND 关系（DecisionEngine 默认 AND，无需额外字段） |
| `channel` | `campaign.channel` |
| `schedule.sendAt` | `campaign.next_trigger_at` + `start_time` |
| `schedule.type=ONCE` | `campaign.trigger_type=SCHEDULED`，`cron_expression=null`，next_trigger_at=sendAt |
| `frequencyCap.maxTimes` + `windowHours=24` | `campaign.user_daily_limit` |
| `frequencyCap.maxTimes` + `windowHours=168` | `campaign.campaign_weekly_limit` |
| `promotionFacts` + 文案 | `campaign.message_template`（确认时由运营选择文案 variant，或取 DIRECT_BENEFIT 默认） |

DSL 仅支持 ONCE 单次发送，因此 `trigger_type` 固定 SCHEDULED，`event_types=null`。

---

## 10. 预计风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| `ProfileService.getWindowMetrics` 走 Redis 缓存，人群预估全量扫描时可能击穿缓存 | 估算慢 | 人群估算采用 MySQL 直查 `user_behavior_summary` + `user_tag`，不挤占实时缓存 |
| 现有 `UserProfile` 不含 `member_level` / `preferred_category`，AI 引用会失败 | DSL 校验失败 | `AiFieldRegistry` 中标 `enabled=false`，Prompt 中不暴露 |
| 现有 `Campaign` 无 `objective` 字段 | 信息丢失 | 写入 `description` 前缀，复盘时解析 |
| `campaign_performance_summary` 需要全站基线 | 计算开销 | 第一版基线为近 30 天同类活动均值，缓存 1 小时 |
| AI 失败可能影响主链路 | 业务受损 | `AiModelClient` 调用全部包 try-catch，AI 异常不抛到 Campaign 业务层；`enabled=false` 时所有 AI 接口返回 `AI_DISABLED` |
| MapperScan 扩展可能影响启动 | 启动失败 | AI Mapper 路径独立，扩展安全 |
| FakeAiModelClient 必须支持 4 种 taskType 的固定 JSON | 测试不可信 | 每个 taskType 一个 fixture 文件，按 taskType 分发 |
| OpenAI Compatible 客户端需要 HTTP 调用 | 引入依赖 | 使用 Spring 自带 `RestTemplate`/`WebClient`，不引入额外 SDK |
| `attribution_record` 关联 `target_event_id`，复盘转化率时需 Join `delivery_record`+`click_event` | SQL 复杂 | 复盘 SQL 集中在 `CampaignReviewService.computePerformance`，单条 SQL 聚合 |
| `user_preference_unsubscribe` 表不存在 | unsubscribeCount 永远 0 | 第一版不返退订率，待后续补表 |

---

## 11. 实施顺序

```
阶段0  现状分析 ──────────────── 本文（已完成）
阶段1  AI 基础设施              pulseflow-ai 骨架 + AiModelClient + Fake/OpenAI + 配置 + 错误码 + 审计
阶段2  AI Campaign 创建         DSL + AiFieldRegistry + Prompt + 解析 + 校验 + 草稿表 + /parse API
阶段3  人群预估与确认            DSL→Rule + AudiencePreviewService + 草稿过期 + /from-ai-draft
阶段4  人群洞察与策略            聚合指标 + 基线 + Insight Prompt + evidence 校验 + /insight API
阶段5  文案生成                  Content Prompt + 三种文案 + 事实校验 + /contents API
阶段6  活动复盘                  CampaignReviewJob + Performance Summary + Review Prompt + /review API
阶段7  测试与回归                单元 + 集成 + Testcontainers
```

每阶段完成后输出阶段报告（§19 格式）。

---

## 12. 关键设计决策

1. **AI 模块为可选增强**：`pulseflow.ai.enabled=false` 时所有 AI Bean 不装配，系统照常运行。
2. **AI 不直接执行**：所有 AI 输出经校验后保存草稿，确认时调用 `DecisionEngine` 现有路径。
3. **字段注册中心为唯一来源**：Prompt 中字段清单由 `AiFieldRegistry` 动态注入，Java 与 Prompt 不重复维护。
4. **聚合后才送 AI**：人群洞察只送聚合指标，不送 userId 列表；复盘只送比率，不送明细。
5. **JSON 结构化输出**：4 个 AI 功能全部要求 JSON，`AiOutputParser` 处理 markdown 包裹和非法 JSON。
6. **Fake Client 优先**：CI 和本地默认 `mock-enabled=true`，真实模型测试需显式开启。
7. **API Key 只走环境变量**：`PULSEFLOW_AI_API_KEY`，禁止入库或入 git。
8. **复盘幂等**：`campaign_performance_summary` 和 `campaign_ai_review` 均以 `campaign_id` 为 UK，UPSERT。
9. **审计强制**：每次 AI 调用写 `ai_generation_record`，含 requestId/provider/model/prompt_version/tokens/latency/status。
10. **错误码分层**：`AI_PROVIDER_*`（基础设施）/ `AI_INVALID_JSON`/`AI_OUTPUT_SCHEMA_INVALID`（解析）/ `AI_UNKNOWN_FIELD`/`AI_INVALID_OPERATOR`（业务校验）分开。
