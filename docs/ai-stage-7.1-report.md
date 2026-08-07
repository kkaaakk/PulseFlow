# PulseFlow AI Campaign Copilot — 阶段 7.1 + 7.2：AI 集成收口与工程可靠性报告

> 阶段 7.1 + 7.2 产出。本文档是 AI Campaign Copilot 从"核心编码完成"到"工程上可信"的完整收口验证报告。
>
> 报告日期：2026-08-06（7.1）/ 2026-08-07（7.2 更新）
> 验证范围：全模块编译 + 全量单元/集成测试 + 双模式启动 + 数据库迁移 + 并发抢占 + API 权限与资源归属 + 状态机拆分 + 核心链路回归 + CI 强制 Docker 测试

---

## 1. 本阶段目标

### 阶段 7.1：AI 集成收口

集中解决跨模块集成、完整回归、并发幂等、指标口径、启动模式、端到端验证六类工程可信度问题。

### 阶段 7.2：工程收口（本轮）

针对 7.1 报告评审反馈的 7 项工程缺口进行最终收紧：

1. `mvn clean verify` 必须自动执行 `*IT`（failsafe 插件）
2. CI 强制运行 Docker 集成测试（GitHub Actions + enforcer）
3. 资源归属权限校验（防越权操作他人草稿/活动）
4. 拆分可重试失败与数据不足状态（避免无效重扫）
5. 核心模块补 10 个高价值测试（CDP/Campaign 主链路回归保护）
6. 数据口径限制同步返回前端（DataQuality 元数据）
7. 僵死锁时间与重试参数配置化（不写死在代码中）

---

## 2. 实际完成内容

### 2.1 全模块完整回归 ✅

执行 `mvn clean verify`，9 个 Maven 模块全部 SUCCESS，详见 §9。

### 2.2 两条端到端集成测试 ✅

| 测试类 | 测试数 | 覆盖链路 |
|---|---|---|
| `CampaignCreationFlowTest` | 6 | 自然语言 → DSL 校验 → 草稿 → 确认 → 原 Campaign 服务创建 |
| `CampaignReviewFlowTest` | 13 | XXL-JOB 扫描 → 指标汇总 → AI 复盘 → evidence 校验 → 保存/查询；数据未就绪宽限期重试；复盘归属校验 |

**Campaign 创建链路验证点**（`CampaignCreationFlowTest`）：
- VALIDATED 草稿确认后通过原 `CampaignMapper` 插入真实 Campaign + rules（AI 不绕过）
- 非 VALIDATED 草稿不能确认（409）
- 过期草稿不能确认（409）
- 缺失草稿返回 404
- 重复确认幂等（只创建一次 Campaign）
- 正式 Campaign 描述嵌入 objective 标记，便于复盘提取

**活动复盘链路验证点**（`CampaignReviewFlowTest`）：
- 已 SUCCESS 的 Campaign 跳过（不重复调用 AI）
- 正在 PROCESSING 的 Campaign 跳过（CAS 锁返回 0）
- 全新 Campaign 抢占锁并调用 AI
- AI 失败时标记 RETRYABLE_FAILED（不卡在 PROCESSING）
- AI 失败时聚合指标仍然保存（summary 在 AI 调用前已 compute）
- 数据不足时标记 SKIPPED_INSUFFICIENT_DATA（不调用 AI、不强生成结论）
- PROCESSING 状态下 regenerate 抛 409
- SUCCESS 后 regenerate 覆盖旧复盘
- `sentCount=0` 但 `audience>0` 且在宽限期内 → 标记 DATA_NOT_READY（可重试，不永久跳过）
- `requireCampaignOwner`：无登录态 / 历史 `created_by=null` / 非归属者 → 403；归属者 → 通过

### 2.3 XXL-JOB 并发抢占与重复 AI 调用防护 ✅

在 `campaign_ai_review` 表上实现 **数据库状态机 + 条件 UPDATE（CAS 锁）**：

```
(absent) --insert PENDING--> PENDING --CAS UPDATE--> PROCESSING --AI--> SUCCESS
                                                          |
                                                     AI 失败(可重试)
                                                          v
                                                RETRYABLE_FAILED --(next_retry_at 到)--> PROCESSING
                                                          |
                                                     重试上限 / 校验失败
                                                          v
                                              PERMANENT_FAILED (终态)

audience=0 / 宽限期后 sentCount=0 ──> SKIPPED_INSUFFICIENT_DATA (终态，不重试)

宽限期内 sentCount=0 ──> DATA_NOT_READY --(next_retry_at=endTime+delay 到)--> PROCESSING
                            (可重试，等待消费链路归集)
```

**核心机制**（`CampaignReviewService`）：
1. 无行时 INSERT PENDING（`INSERT IGNORE` 处理并发插入冲突）
2. 条件 UPDATE 抢占：`SET status='PROCESSING' WHERE status IN ('PENDING','RETRYABLE_FAILED','DATA_NOT_READY') OR (status='PROCESSING' AND locked_at < NOW()-:lockStaleMinutes)`
3. 只有 `affected=1` 的执行器继续调用 LLM，其余静默跳过
4. PROCESSING 锁超过配置化 `lock-stale-minutes`（默认 10）视为僵死，允许抢占
5. AI 可重试失败标记 RETRYABLE_FAILED + 指数退避 `next_retry_at`；超过 `max-retry-count`（默认 3）转为 PERMANENT_FAILED
6. 数据就绪度三态判定（`assessDataReadiness`）：
   - `targetAudienceCount <= 0` → SKIPPED_INSUFFICIENT_DATA（终态，无目标人群永远不会变）
   - `sentCount <= 0` 且在 `data-ready-delay-minutes` 宽限期内 → DATA_NOT_READY（可重试，`next_retry_at = endTime + delay`），等待消费链路归集
   - `sentCount <= 0` 且宽限期已过 → SKIPPED_INSUFFICIENT_DATA（终态，数据确实缺失而非延迟）

**防重复 AI 调用保证**：数据库 upsert 防重复行 + CAS 锁防重复 LLM 调用，双重保护。

### 2.4 指标口径和数据版本文档 ✅

产出 `docs/ai-metrics-glossary.md`，定义：
- 通用约定：比例 0~1 / 金额 2 位小数 / 分母为 0 返回 0 / 浮点容差 ±0.01
- "提高 X%"（相对提升）vs "提高 X 个百分点"（绝对差）严格区分
- 人群洞察指标 11 项（数据来源 / 时间窗口 / 计算方式 / 空值含义）
- 活动复盘指标 7 项
- evidence 校验器对接规则
- 已知口径风险（baseline 与目标人群同候选池、`cartWithoutPurchaseRate` 代理口径等）

### 2.5 AI 开关双模式启动测试 ✅

`AiModeBootstrapIT` 使用 `ApplicationContextRunner` 轻量验证：

| 模式 | 配置 | 验证结果 |
|---|---|---|
| AI 关闭 | `pulseflow.ai.enabled=false` | Spring Context 加载成功，所有 AI Bean 缺失 |
| AI 开启 | `enabled=true, mock-enabled=true` | Context 加载成功，AI 核心 Bean 全部装配，AiModelClient 为 FakeAiModelClient |
| AI 开启非 mock | `enabled=true, mock-enabled=false` | AiModelClient 不是 FakeAiModelClient（走 OpenAI Compatible） |

### 2.6 数据库迁移验证 ✅

**Flyway V4 迁移**（`V4__ai_review_state_machine.sql`）：
- 放宽 `campaign_ai_review` 的 NOT NULL 约束
- 新增 `locked_by` / `locked_at` / `version` 列
- 新增索引 `idx_ai_review_status (status, updated_at)` 支持扫描待复盘行

**Flyway V5 迁移**（`V5__review_status_split_and_ownership.sql`，7.2 新增）：
- `campaign` 表新增 `created_by BIGINT NULL`（资源归属）
- `campaign_ai_review` 新增 `failure_code` / `retryable` / `retry_count` / `next_retry_at` 列
- 现有 `FAILED` 数据迁移为 `RETRYABLE_FAILED`（保守策略：可重试）
- 索引重建为 `idx_ai_review_status_retry (status, next_retry_at, updated_at)` 支持重试调度

### 2.7 API 权限与状态码检查 ✅

**权限模型**：Sa-Token 全局拦截器在 `/api/**` 强制登录，operatorId 从 `StpUtil.getLoginId()` 获取（服务端权威，忽略请求体 `operatorId` 字段，防止前端伪造）。

**状态码映射**（`GlobalExceptionHandler`）：

| 异常 | HTTP 状态码 | 含义 |
|---|---|---|
| `AiDisabledException` | 503 | AI 功能关闭 |
| `AiProviderException` | 503 | 模型提供商不可用/超时/限流 |
| `AiOutputInvalidException` | 422 | AI 输出能返回但未通过业务校验 |
| `AiResourceNotFoundException` | 404 | 草稿/Campaign 不存在 |
| `AiConflictException` | 409 | 草稿状态冲突/已确认/已过期/正在生成 |
| `AiForbiddenException` | 403 | 越权操作他人草稿或活动（7.2 新增） |
| regenerate 频繁 | 429 | 60 秒冷却内重复调用 |

---

### 2.8 maven-failsafe-plugin 自动执行集成测试 ✅（7.2）

**问题**：父 `pom.xml` 未配置 `maven-failsafe-plugin`，`*IT.java` 不会被 `mvn clean verify` 自动执行。

**修复**：在父 `pom.xml` 添加 failsafe 插件，绑定 `integration-test` + `verify` 目标。

**效果**：`mvn clean verify` 单条命令执行 `*Test`（surefire）+ `*IT`（failsafe）。`AiModeBootstrapIT` 自动执行 6 tests，Docker 相关 IT 默认跳过。

### 2.9 资源归属权限校验 ✅（7.2）

**问题**：登录校验 ≠ 资源归属校验。用户 A 可猜到用户 B 的 draftId 修改其草稿，或查看不属于自己的活动复盘。

**修复**：
- `CampaignAiDraftService.requireDraftOwner(draft, operatorId)`：草稿操作前校验 `draft.operatorId == 当前登录用户`，不匹配抛 `AiForbiddenException`（403）
- `CampaignReviewService.requireCampaignOwner(campaignId, operatorId)`：复盘查询/重新生成前校验 `campaign.createdBy == 当前登录用户`。**历史数据 `created_by=null` 默认拒绝普通用户访问**（防越权），仅创建者本人可读取自己的复盘
- `AiCampaignReviewController` 的 `GET /review` 与 `POST /review/regenerate` 在任何模型调用前先调 `requireCampaignOwner`
- `Campaign` 实体新增 `createdBy` 字段；**唯一的 Campaign 生产创建入口 `CampaignAiDraftService.confirmAndCreate` 已写入 `createdBy(operatorId)`**，项目无其他普通 Campaign 创建 REST 接口
- `GlobalExceptionHandler` 添加 `AiForbiddenException → 403` 映射

### 2.10 状态机拆分：可重试 vs 数据未就绪 vs 数据不足 vs 永久失败 ✅（7.2）

**问题**：AI 超时和数据不足都标 FAILED，XXL-JOB 会无限重扫数据不足的活动；且 `sentCount=0` 可能只是消费链路延迟，却被永久标记为数据不足。

**修复**：
- 新状态：`RETRYABLE_FAILED`（AI 超时/5xx，可重试）、`DATA_NOT_READY`（sentCount=0 但 audience>0 且在宽限期内，可重试）、`SKIPPED_INSUFFICIENT_DATA`（数据不足，终态不重试）、`PERMANENT_FAILED`（重试上限/校验失败，终态）
- 新字段：`failure_code` / `retryable` / `retry_count` / `next_retry_at`
- CAS 锁条件改为 `status IN ('PENDING','RETRYABLE_FAILED','DATA_NOT_READY')`
- Job 扫描排除 `SKIPPED_INSUFFICIENT_DATA` 和 `PERMANENT_FAILED` 终态；对 `RETRYABLE_FAILED` 和 `DATA_NOT_READY` 均尊重 `next_retry_at` 退避，避免每轮重复评估
- 重试采用指数退避：`next_retry_at = now + 2^retryCount 分钟`
- 超过 `max-retry-count`（默认 3）转为 `PERMANENT_FAILED`
- `DATA_NOT_READY` 的 `next_retry_at = campaign.endTime + data-ready-delay-minutes`，给消费链路留出归集时间

### 2.11 核心模块 10 个高价值测试 ✅（7.2）

**问题**：AI 有 77 个测试但核心 CDP/Campaign 模块零测试，项目呈现失衡。

**修复**：新增 10 个测试覆盖核心主链路：

| 测试类 | 模块 | 测试数 | 覆盖点 |
|---|---|---|---|
| `DecisionEngineTest` | pulseflow-campaign | 6 | PROFILE tag EQ 匹配、PROFILE metric GTE 阈值、EVENT property 匹配、FREQUENCY 频控超限阻断、DuplicateKey 幂等跳过、DELAYED 延迟任务调度 |
| `AttributionServiceTest` | pulseflow-campaign | 3 | Last-touch 归因匹配（MATCHED）、24h 窗口无点击过期（EXPIRED）、DuplicateKey 归因幂等 |
| `EventPersistenceServiceTest` | pulseflow-event | 1 | 重复 eventId 幂等（加载 DB 规范记录，不抛异常） |

### 2.12 数据口径限制返回前端 ✅（7.2）

**问题**：baseline=候选池、代理指标、null 指标只写在开发文档，前端和运营不知情。

**修复**：
- `AiCampaignDtos.DataQuality` 内部类：`baselineType` / `proxyMetrics` / `unavailableMetrics`
- `AudienceInsightService.InsightOutput` 携带 `DataQuality`
- `AiCampaignController.insight` 接口响应填充 `dataQuality` 字段
- 前端可展示："当前对比基线来自候选用户池，并非全站数据。"

### 2.13 配置化锁超时与重试参数 ✅（7.2）

**问题**：`LOCK_STALE_MINUTES = 10` 硬编码在代码中，修改需重新发版。

**修复**：移至 `AiFeatureProperties.Review` 配置类：

```yaml
pulseflow:
  ai:
    review:
      lock-stale-minutes: 10
      max-retry-count: 3
      regenerate-cooldown-seconds: 60
      data-ready-delay-minutes: 10   # sentCount=0 宽限期，超过才判永久数据不足
```

### 2.14 CI 强制运行 Docker 集成测试 ✅（7.2）

**问题**：Docker 测试依赖手动 CLI 验证，CI 中可能被永久跳过而不被发现。

**修复**：
- 创建 `.github/workflows/ci.yml`：GitHub Actions 工作流，设 `CI=true` + `PULSEFLOW_TEST_DOCKER=true`，运行 `mvn clean verify`
- 父 `pom.xml` 添加 `ci-enforce-docker-tests` profile：当 `env.GITHUB_ACTIONS=true` 时，enforcer 检查 `env.PULSEFLOW_TEST_DOCKER=true`，否则构建失败
- 使用 `GITHUB_ACTIONS` 而非 `CI` 变量，避免本地 IDE（如 Trae CN 设置 `CI=true`）误触发

**三场景验证**：
- 本地无 `GITHUB_ACTIONS`：profile 不激活，构建正常 ✅
- `GITHUB_ACTIONS=true` 但无 `PULSEFLOW_TEST_DOCKER`：enforcer 失败 ✅
- `GITHUB_ACTIONS=true` + `PULSEFLOW_TEST_DOCKER=true`：enforcer 通过 ✅

**CI 真实跑通**（最终验收）：推送后 GitHub Actions Runner 上 `mvn clean verify` 全绿，boot 模块 11 个 IT 全跑零跳过零失败——`FlywayMigrationIT`（2，Testcontainers MySQL 8.0 迁移 V1~V5）+ `EventIdempotentConsumptionIT`（3，事件幂等消费）+ `AiModeBootstrapIT`（6，AI 双模式启动）。run 31139293006，BUILD SUCCESS。过程中修复 4 个 CI-only 潜伏 bug（详见 §12）。

---

## 3. 新增文件

| 文件 | 用途 |
|---|---|
| `docs/ai-metrics-glossary.md` | AI 指标口径字典 |
| `docs/ai-stage-7.1-report.md` | 本报告 |
| `pulseflow-ai/.../integration/CampaignCreationFlowTest.java` | Campaign 创建端到端集成测试（6 tests） |
| `pulseflow-ai/.../integration/CampaignReviewFlowTest.java` | 活动复盘端到端集成测试（8 tests） |
| `pulseflow-boot/.../db/migration/V4__ai_review_state_machine.sql` | V4 迁移：状态机列 + 索引 |
| `pulseflow-boot/.../db/migration/V5__review_status_split_and_ownership.sql` | V5 迁移：状态拆分 + 资源归属（7.2） |
| `pulseflow-ai/.../support/AiForbiddenException.java` | 越权异常类 → 403（7.2） |
| `pulseflow-campaign/.../decision/DecisionEngineTest.java` | 决策引擎 6 个核心测试（7.2） |
| `pulseflow-campaign/.../attribution/AttributionServiceTest.java` | 归因服务 3 个核心测试（7.2） |
| `pulseflow-event/.../service/EventPersistenceServiceTest.java` | 事件持久化幂等测试（7.2） |
| `.github/workflows/ci.yml` | GitHub Actions CI 工作流（7.2） |

---

## 4. 修改文件

| 文件 | 修改内容 |
|---|---|
| `pom.xml`（父） | 添加 maven-failsafe-plugin + ci-enforce-docker-tests profile（7.2） |
| `CampaignReviewService.java` | CAS 锁抢占 + 状态拆分（RETRYABLE_FAILED/DATA_NOT_READY/SKIPPED/PERMANENT）+ 数据就绪度三态判定 + 指数退避重试 + 配置化参数 + `requireCampaignOwner` 归属校验 |
| `CampaignAiDraftService.java` | 添加 `requireDraftOwner` 资源归属校验 + `createdBy` 写入 |
| `AiCampaignController.java` | operatorId 从 Sa-Token 获取 + insight 响应填充 DataQuality |
| `AiCampaignReviewController.java` | getReview/regenerate 调用 `requireCampaignOwner` 归属校验 + cooldown 从 properties 读取 |
| `GlobalExceptionHandler.java` | 添加 `AiForbiddenException → 403` 映射 |
| `Campaign.java` | 添加 `createdBy` 字段 |
| `AiFeatureProperties.java` | 添加 `Review` 配置类（lockStaleMinutes/maxRetryCount/regenerateCooldownSeconds/dataReadyDelayMinutes） |
| `AiCampaignDtos.java` | 添加 `DataQuality` 内部类 + `InsightResponse.dataQuality` |
| `AudienceInsightService.java` | `InsightOutput` 携带 `DataQuality` 元数据 |
| `CampaignReviewJob.java` | 扫描排除 SKIPPED/PERMANENT 终态 + 对 RETRYABLE_FAILED/DATA_NOT_READY 尊重 `next_retry_at` 退避 + DATA_NOT_READY 计入 skipped |
| `CampaignReviewFlowTest.java` | 适配新状态 + DATA_NOT_READY 宽限重试测试 + requireCampaignOwner 4 场景测试（13 tests） |
| `AiModeBootstrapIT.java` | 添加 `SimpleMeterRegistry` Bean + Docker IT 加 `@EnabledIfEnvironmentVariable` |
| `pulseflow-campaign/pom.xml` | 添加 `spring-boot-starter-test` 测试依赖（7.2） |
| `pulseflow-event/pom.xml` | 添加 `spring-boot-starter-test` 测试依赖（7.2） |

---

## 5. 数据库变更

### V4 迁移（`V4__ai_review_state_machine.sql`）

```sql
ALTER TABLE campaign_ai_review
    MODIFY COLUMN performance_summary_id BIGINT NULL,
    MODIFY COLUMN review_json JSON NULL,
    MODIFY COLUMN model VARCHAR(64) NULL,
    MODIFY COLUMN prompt_version VARCHAR(32) NULL;
ALTER TABLE campaign_ai_review
    ADD COLUMN locked_by VARCHAR(64) NULL,
    ADD COLUMN locked_at DATETIME NULL,
    ADD COLUMN version INT NOT NULL DEFAULT 0;
CREATE INDEX idx_ai_review_status ON campaign_ai_review (status, updated_at);
```

### V5 迁移（`V5__review_status_split_and_ownership.sql`，7.2 新增）

```sql
-- 资源归属
ALTER TABLE campaign ADD COLUMN created_by BIGINT NULL;

-- 状态拆分字段
ALTER TABLE campaign_ai_review
    ADD COLUMN failure_code VARCHAR(64) NULL,
    ADD COLUMN retryable TINYINT(1) NOT NULL DEFAULT 1,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at DATETIME NULL;

-- 现有 FAILED 迁移为 RETRYABLE_FAILED（保守策略：可重试）
UPDATE campaign_ai_review SET status='RETRYABLE_FAILED', retryable=1 WHERE status='FAILED';

-- 索引重建：支持重试调度扫描
DROP INDEX idx_ai_review_status ON campaign_ai_review;
CREATE INDEX idx_ai_review_status_retry ON campaign_ai_review (status, next_retry_at, updated_at);
```

---

## 6. 核心设计决策

### 6.1 数据库状态抢占优于 Redis 分布式锁

选择 `PENDING → PROCESSING → SUCCESS/RETRYABLE_FAILED/SKIPPED_INSUFFICIENT_DATA/PERMANENT_FAILED` 状态机 + 条件 UPDATE 作为 CAS 锁，而非 Redis 分布式锁，因为：
- 状态可观测（直接查表看哪个执行器在处理）
- 失败重试简单（RETRYABLE_FAILED 状态下次 Job 自动重试）
- 无额外中间件依赖
- 配合唯一索引 `uk_campaign_ai_review(campaign_id)` 防重复行

### 6.2 数据不足不强生成结论（三态判定）

复盘前用 `assessDataReadiness` 区分三种数据不足情形，避免把"消费链路延迟"误判成"永久数据不足"：

- `targetAudienceCount <= 0` → `SKIPPED_INSUFFICIENT_DATA`（终态）：无目标人群，永远不会变，不调用 AI
- `sentCount <= 0` 且在 `data-ready-delay-minutes` 宽限期内 → `DATA_NOT_READY`（可重试）：Campaign 刚结束、消费链路可能仍在归集，`next_retry_at = endTime + delay`，Job 到点重试
- `sentCount <= 0` 且宽限期已过 → `SKIPPED_INSUFFICIENT_DATA`（终态）：数据确实缺失而非延迟

任何数据不足情形都不调用 AI、不让模型从空输入编造结论。

### 6.3 AI 失败不丢指标

`PerformanceSummaryCalculator.compute()` 在 AI 调用之前执行并持久化。AI 失败时指标已保存，复盘可后续重试，不因 AI 不可用而丢失活动数据。

### 6.4 可重试 vs 数据未就绪 vs 永久失败（7.2）

- `RETRYABLE_FAILED`：AI 超时/5xx，指数退避重试，超过 max-retry-count 转 `PERMANENT_FAILED`
- `DATA_NOT_READY`：sentCount=0 但 audience>0 且在宽限期内，`next_retry_at = endTime + delay`，到点重试
- `SKIPPED_INSUFFICIENT_DATA`：audience=0 或宽限期后 sentCount 仍为 0，终态不重试
- `PERMANENT_FAILED`：重试上限或校验失败，终态不重试

这避免 XXL-JOB 无效重扫"数据不足"的活动，同时保证暂时性故障和数据归集延迟可自动恢复，不被误判为终态。

### 6.5 资源归属校验（7.2）

Service 层在操作前校验资源归属，不只校验登录态：
- 草稿侧：`requireDraftOwner` 校验 `draft.operatorId == 当前登录用户`
- 复盘侧：`requireCampaignOwner` 校验 `campaign.createdBy == 当前登录用户`
- **历史数据 `created_by=null` 默认拒绝普通用户访问**（不采用"null=所有人可见"的宽松策略，避免越权漏洞），仅创建者本人可读写自己的资源
- 唯一 Campaign 生产创建入口 `confirmAndCreate` 已写入 `createdBy`，新数据不会出现 null

越权操作返回 403，且复盘的归属校验在任何模型调用前执行（不浪费 Token）。

### 6.6 Testcontainers 默认跳过 + CI 强制执行（7.2）

本地 Docker Desktop 29.x 与 Testcontainers 兼容问题，通过 `@EnabledIfEnvironmentVariable` 默认跳过。CI 环境通过 GitHub Actions + enforcer 强制 `PULSEFLOW_TEST_DOCKER=true`，防止 Docker 测试被永久跳过。

### 6.7 operatorId 服务端权威

所有 AI Controller 的 operatorId 从 `StpUtil.getLoginId()` 获取，忽略请求体中的 `operatorId` 字段。每个 AI 操作可追溯到认证用户，前端无法伪造操作者。

---

## 7. 构建结果

```
mvn clean verify
```

```
Reactor Summary for PulseFlow 1.0.0-SNAPSHOT:
PulseFlow .......................................... SUCCESS [  2.530 s]
PulseFlow Common ................................... SUCCESS [  7.233 s]
PulseFlow Profile .................................. SUCCESS [  2.947 s]
PulseFlow Campaign ................................. SUCCESS [  9.414 s]
PulseFlow Event .................................... SUCCESS [  7.357 s]
PulseFlow AI ....................................... SUCCESS [ 14.922 s]
PulseFlow Job ...................................... SUCCESS [  3.237 s]
PulseFlow Simulator ................................ SUCCESS [  2.444 s]
PulseFlow Boot ..................................... SUCCESS [ 12.832 s]
BUILD SUCCESS
Total time:  01:03 min
```

**9 个模块全部 SUCCESS，0 编译错误。`mvn clean verify` 单条命令执行所有单元测试 + 非 Docker 集成测试。**

---

## 8. 定向测试结果

### pulseflow-ai 模块（82 tests，0 失败）

| 测试类 | 测试数 | 结果 |
|---|---|---|
| `AiOutputParserTest` | 9 | ✅ 全过 |
| `CampaignDslValidatorTest` | 16 | ✅ 全过 |
| `ContentFactValidatorTest` | 13 | ✅ 全过 |
| `InsightEvidenceValidatorTest` | 7 | ✅ 全过 |
| `ReviewEvidenceValidatorTest` | 9 | ✅ 全过 |
| `SensitiveDataSanitizerTest` | 9 | ✅ 全过 |
| `CampaignCreationFlowTest` | 6 | ✅ 全过 |
| `CampaignReviewFlowTest` | 13 | ✅ 全过（含 DATA_NOT_READY 宽限重试 + requireCampaignOwner 归属校验） |
| **合计** | **82** | **0 失败 / 0 错误 / 0 跳过** |

### pulseflow-campaign 模块（9 tests，0 失败）— 7.2 新增

| 测试类 | 测试数 | 结果 |
|---|---|---|
| `DecisionEngineTest` | 6 | ✅ PROFILE tag/metric 匹配 + EVENT property 匹配 + FREQUENCY 频控阻断 + DuplicateKey 幂等 + DELAYED 调度 |
| `AttributionServiceTest` | 3 | ✅ Last-touch MATCHED + 无点击 EXPIRED + DuplicateKey 幂等 |
| **合计** | **9** | **0 失败 / 0 错误 / 0 跳过** |

### pulseflow-event 模块（1 test，0 失败）— 7.2 新增

| 测试类 | 测试数 | 结果 |
|---|---|---|
| `EventPersistenceServiceTest` | 1 | ✅ 重复 eventId 幂等（加载 DB 规范记录） |

### pulseflow-common 模块（6 tests，0 失败）

| 测试类 | 测试数 | 结果 |
|---|---|---|
| `DedupKeyUtilTest` | 6 | ✅ 全过 |

### pulseflow-boot 模块（集成测试，failsafe 自动执行）

| 测试类 | 测试数 | 结果 |
|---|---|---|
| `AiModeBootstrapIT$AiDisabledMode` | 1 | ✅ Context 加载成功，AI Bean 缺失 |
| `AiModeBootstrapIT$AiEnabledMode` | 5 | ✅ Context 加载成功，AI Bean 全装配 + Fake/非 Fake Client |
| `FlywayMigrationIT` | 2 | ⏭️ 跳过（需 Docker，CI 环境启用） |
| `EventIdempotentConsumptionIT` | 3 | ⏭️ 跳过（需 Docker，CI 环境启用） |

> **7.2 改进**：`AiModeBootstrapIT` 现在通过 `mvn clean verify` 的 failsafe 插件自动执行，无需手动 `-Dtest=` 补跑。

---

## 9. 完整回归结果

| 模块 | 编译 | 单元测试 | 集成测试 | 状态 |
|---|---|---|---|---|
| pulseflow (pom) | ✅ | — | — | SUCCESS |
| pulseflow-common | ✅ | 6 tests, 0 fail | — | SUCCESS |
| pulseflow-profile | ✅ | 0 tests | — | SUCCESS |
| pulseflow-campaign | ✅ | 9 tests, 0 fail (**7.2 新增**) | — | SUCCESS |
| pulseflow-event | ✅ | 1 test, 0 fail (**7.2 新增**) | — | SUCCESS |
| pulseflow-ai | ✅ | 82 tests, 0 fail | — | SUCCESS |
| pulseflow-job | ✅ | 0 tests | — | SUCCESS |
| pulseflow-simulator | ✅ | 0 tests | — | SUCCESS |
| pulseflow-boot | ✅ | — | 11 tests (6 run + 5 Docker IT), 0 fail | SUCCESS |

**单元测试合计：98 tests, 0 failures**
**集成测试合计：11 tests (6 run + 5 Docker IT), 0 failures**
**总计：109 tests, 0 failures**

> 本地（Docker Desktop 29.x 与 Testcontainers 不兼容）5 个 Docker IT 默认跳过；**CI 环境通过 enforcer 强制 `PULSEFLOW_TEST_DOCKER=true` 真实执行，已验证全绿**（详见 §2.14 / §12）。

**结论：全模块 `mvn clean verify` BUILD SUCCESS。failsafe 插件自动执行 `*IT`，核心模块有回归保护，无回归。CI 已真实跑通 Docker 集成测试。**

---

## 10. 失败测试分类

| 分类 | 测试 | 说明 |
|---|---|---|
| 本轮新增失败 | 无 | — |
| 历史已有失败 | 无 | — |
| 环境依赖跳过 | `FlywayMigrationIT` (2) / `EventIdempotentConsumptionIT` (3) | Testcontainers + Docker Desktop 29.x 兼容问题，本地 `@EnabledIfEnvironmentVariable` 默认跳过。**CI 环境通过 enforcer 强制 `PULSEFLOW_TEST_DOCKER=true` 真实执行，已验证 11 IT 全跑全过（0 skipped）**（7.2） |

---

## 11. 已知问题与局限

1. **Testcontainers 本地兼容性**：Docker Desktop 29.x 的 `_ping` 返回 Status 400，Testcontainers 无法初始化。本地迁移验证改用 docker CLI 直跑 MySQL 容器。**CI 环境通过 GitHub Actions + enforcer 强制执行 Docker 测试，已获得完整绿色流水线**（7.2 解决）。

2. **指标基线口径**：v1 用候选池本身作为 site-wide baseline（非真实全站），且候选池有 50000 上限。**7.2 已通过 `DataQuality` 元数据返回前端**，展示"当前对比基线来自候选用户池"。

3. **`cartWithoutPurchaseRate` 代理口径**：当前实现为"活跃但未消费"代理指标。**7.2 已在 `DataQuality.proxyMetrics` 中标记**，前端可提示。

4. **`topCategories` / `memberLevelDistribution` 未提供**：**7.2 已在 `DataQuality.unavailableMetrics` 中标记**，前端可提示"暂无数据"。

5. **核心模块测试覆盖仍可增强**：7.2 新增 10 个测试覆盖决策引擎、归因、事件幂等三大核心路径，但频控边界、标签规则计算、Redis 状态降级等可后续补充。

6. **文案重复类型校验**：当前有"重复类型"校验，未引入内容相似度检查。v1 可接受。

7. **PII 检测覆盖**：`SensitiveDataSanitizer` 覆盖手机号、身份证、邮箱，详细地址/设备 ID/Cookie 等可后续扩展。

---

## 12. 下一阶段建议

阶段 7.1 + 7.2 完成后，AI Campaign Copilot 已达到工程上可信状态，**不建议继续堆功能**。用户评审提出的三个最终验收项**已全部完成**：

- ✅ **统计数据归集延迟不被误判为永久数据不足**：新增 `DATA_NOT_READY` 状态 + `data-ready-delay-minutes` 宽限期，`sentCount=0` 在宽限期内可重试，仅 `audience=0` 或宽限期后才永久跳过
- ✅ **所有 Campaign 创建入口写入 `created_by`**：确认唯一生产创建入口 `confirmAndCreate` 已写入，无其他普通 Campaign 创建接口；历史 `null` 记录默认拒绝普通用户访问
- ✅ **CI 首次运行验证**：推送代码到 GitHub，CI 工作流在 Runner 上真实跑通 Docker 集成测试。`FlywayMigrationIT`（2 tests）+ `EventIdempotentConsumptionIT`（3 tests）在 Testcontainers MySQL 8.0 上全跑全过，boot 模块 11 IT 零跳过零失败，获得完整绿色流水线（run 31139293006）。

**CI 通过过程中修复的 4 个 CI-only 潜伏 bug**（本地因 Testcontainers 跳过从未暴露，CI 强制执行后才显现）：

1. `flyway-mysql` 被父 pom 锁成 `10.12.0`，与 `flyway-core` 9.22.3 不匹配 → `AbstractMethodError: MySQLDatabase.ensureSupported()`。改为 `${flyway.version}` 对齐。
2. `EventIdempotentConsumptionIT.TestApp` 用 `@TestConfiguration`（非 `@SpringBootConfiguration`）→ `@SpringBootTest(classes=...)` 找不到启动配置源。改用 `@SpringBootConfiguration`。
3. Redisson 3.29.0 注册的是 `RedissonAutoConfigurationV2`（非 `RedissonAutoConfiguration`），exclude 旧类名失败。后改为 `@ImportAutoConfiguration` 只导入数据层自动配置，从根上避免 Sa-Token Redis / Redisson / Kafka / XXL-JOB 排除链不收敛。
4. `FlywayMigrationIT` 仍校验 V5 已删除的旧索引 `idx_ai_review_status`（V5 改名为 `idx_ai_review_status_retry`）。更新为新索引名 + 补 V5 列校验。

> 三项验收全部完成后，项目可进入：README 包装 → 架构图 → 演示数据 → 接口演示 → 简历描述 → 面试讲解。当前应停止开发，把已有能力讲清楚、演示稳定。

可选优化方向（非阻塞，不影响收口）：

1. **核心模块测试增强**：补充频控边界、标签规则计算、Review Job 状态过滤、Redis 状态重建/降级测试。
2. **真实模型集成测试**：通过环境变量显式开启，使用固定输入只校验结构/字段/业务约束。
3. **指标基线优化**：接入真实全站基线，区分 baseline 与目标人群的时间窗口版本。
4. **内容相似度检查**：引入字符 n-gram Jaccard 或编辑距离比例。
5. **PII 检测扩展**：覆盖详细地址、设备 ID、Cookie/Token/API Key 格式。
6. **AI 接口轻量去重**（成本保护，非阻塞）：为 `parse`/`insight`/`contents` 增加 `Idempotency-Key` 或 `draftId+taskType` 短时间去重，复用最近成功结果，避免前端重复点击消耗 Token。

---

## 13. 完成度评估

| 维度 | 状态 |
|---|---|
| 全模块编译 | ✅ 9/9 SUCCESS |
| `mvn clean verify` 自动执行 IT | ✅ failsafe 插件（7.2） |
| AI 模块测试 | ✅ 82 tests, 0 fail |
| 核心模块测试 | ✅ 10 tests, 0 fail（7.2 新增） |
| 端到端集成测试 | ✅ 2 条链路 19 tests |
| 并发抢占防护 | ✅ CAS 锁 + 状态机 |
| 状态机拆分 | ✅ RETRYABLE/DATA_NOT_READY/SKIPPED/PERMANENT（7.2） |
| 资源归属校验 | ✅ requireDraftOwner + requireCampaignOwner + createdBy（7.2） |
| 指标口径文档 | ✅ ai-metrics-glossary.md |
| 数据口径返回前端 | ✅ DataQuality 元数据（7.2） |
| AI 双模式启动 | ✅ 关闭/开启/mock/非 mock |
| 数据库迁移 | ✅ V4 + V5 验证通过 |
| API 权限 | ✅ Sa-Token + operatorId 服务端权威 + 资源归属（7.2） |
| 状态码规范 | ✅ 403/404/409/422/429/503 |
| 配置化参数 | ✅ lockStaleMinutes/maxRetryCount/cooldown（7.2） |
| CI 强制 Docker 测试 | ✅ GitHub Actions + enforcer（7.2） |
| CI 真实跑通 Docker IT | ✅ run 31139293006，11 IT 全跑零跳过零失败 |
| 完整回归 | ✅ BUILD SUCCESS, 109 tests, 0 回归 |

**阶段 7.1 + 7.2 完成。AI Campaign Copilot 达到：核心链路完整、AI 边界清晰、具备工程可靠性的事件驱动智能用户运营平台。**
