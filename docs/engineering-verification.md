# PulseFlow Engineering Verification

> 本文档收口项目的自动化测试与 CI 可靠性验证证据。不是开发日志，而是告诉读者：这套代码不是只有 109 个测试，而是真的在 DB 迁移、幂等消费、并发抢占、AI 双模式等关键路径上验证过。

## 1. 自动化测试概览

| 模块 | 测试数 | 覆盖点 |
|---|---|---|
| pulseflow-ai | 82 | NL→DSL 校验、Guardrail、Campaign 创建链路、复盘状态机（含 DATA_NOT_READY 宽限重试 + 资源归属 4 场景） |
| pulseflow-campaign | 9 | DecisionEngine 6（PROFILE/EVENT/FREQUENCY/幂等/延迟）+ Attribution 3（Last-touch/过期/幂等） |
| pulseflow-event | 1 | 事件持久化幂等 |
| pulseflow-common | 6 | 通用工具 |
| pulseflow-boot (IT) | 11 | AiModeBootstrapIT 6 + FlywayMigrationIT 2 + EventIdempotentConsumptionIT 3 |
| **合计** | **109** | **0 failures** |

执行方式：`mvn clean verify` 单条命令，surefire 执行 `*Test` + failsafe 执行 `*IT`。

## 2. 集成测试（Testcontainers + MySQL 8.0）

3 个 `*IT` 类在 CI 环境中真实拉起 `mysql:8.0` 容器：

### FlywayMigrationIT

- 验证 V1~V5 迁移全部成功执行
- 校验 `campaign_ai_review` 表含状态机列（`failure_code`/`retryable`/`retry_count`/`next_retry_at`）
- 校验 `campaign` 表含 `created_by` 列
- 校验新索引 `idx_ai_review_status_retry` 存在、旧索引 `idx_ai_review_status` 已删除

### EventIdempotentConsumptionIT

- 首次 `persist`：`user_event` + `user_metric_hourly` 同事务落库
- 重复 `persist` 同一 `eventId`：不抛异常，从 DB 加载标准事件（幂等）
- 指标桶累加：`INSERT ... ON DUPLICATE KEY UPDATE` 正确累加 `event_count` 和 `amount_sum`

### AiModeBootstrapIT

- `pulseflow.ai.enabled=false` → Spring Context 正常启动，所有 AI Bean 缺失
- `enabled=true, mock-enabled=true` → AI 核心 Bean 全部装配，`AiModelClient` 为 `FakeAiModelClient`
- `enabled=true, mock-enabled=false` → 走 OpenAI Compatible

## 3. 核心可靠性验证

### 3.1 事件幂等

`event_id` 唯一索引 + `DuplicateKeyException` 回查 DB 标准事件，不信任 Kafka 重放 payload。指标桶 `INSERT … ON DUPLICATE KEY UPDATE` 原子累加。

### 3.2 决策补偿恢复

`DecisionEngine` 异常传播契约：业务跳过（规则不匹配/dedup 命中）内部消化；基础设施异常（DB/Redis/Kafka 失败）必须向外抛，由 `CompensationJob` 重试。

### 3.3 触达 dedup 幂等

`delivery_task.dedup_key` 唯一索引防重复触达，频控 `freq:reserved:{taskId}` 保重试不重复扣额。

### 3.4 频控原子性

`FrequencyControlService` 用 Lua 脚本原子完成"判额 + 扣减 + 重试标记"，单次 RTT。

### 3.5 Last-touch 归因

24h 窗口内向前回溯最近一次点击，匹配 Campaign 写 `attribution_record`，`DuplicateKeyException` 幂等。

## 4. AI 工程保护

### 4.1 DSL 校验

六道确定性校验（Java）：字段白名单（`AiFieldRegistry` 12 个受信字段）→ 类型+范围 → 时间合法性 → 频控约束 → 优惠事实服务端权威 → `evidenceKeys` 数字一致性。

### 4.2 并发防重复调用

`campaign_ai_review` 表 CAS 状态机：
- 条件 UPDATE 抢占：`SET status='PROCESSING' WHERE status IN ('PENDING','RETRYABLE_FAILED','DATA_NOT_READY')`
- 只有 `affected=1` 的执行器调用 LLM
- 僵死锁超时自动抢占（`lock-stale-minutes` 配置化，默认 10 分钟）

### 4.3 失败三态拆分

| 状态 | 含义 | 行为 |
|---|---|---|
| `RETRYABLE_FAILED` | AI 超时/5xx | 指数退避重试，超 `max-retry-count`（默认 3）转 `PERMANENT_FAILED` |
| `DATA_NOT_READY` | `sentCount=0` 但 `audience>0` 且在宽限期内 | `next_retry_at = endTime + data-ready-delay-minutes`，到点重试 |
| `SKIPPED_INSUFFICIENT_DATA` | `audience=0` 或宽限期后 `sentCount=0` | 终态，不再重试 |
| `PERMANENT_FAILED` | 重试上限 / 校验失败 | 终态 |

关键设计：`sentCount=0` 不直接判终态——Campaign 刚结束时消费链路可能仍在归集，`data-ready-delay-minutes` 宽限期内标记 `DATA_NOT_READY` 可重试，避免把"数据延迟"误判成"永久数据不足"。

### 4.4 AI 失败不丢指标

`PerformanceSummaryCalculator.compute()` 在 AI 调用之前执行并持久化。AI 失败时指标已保存，复盘可后续重试。

### 4.5 资源归属防越权

- `requireDraftOwner`：校验草稿 `operatorId == 当前登录用户`
- `requireCampaignOwner`：校验 `campaign.createdBy == 当前登录用户`
- 历史 `created_by=null` 数据**默认拒绝**普通用户访问（防猜 ID 越权）
- `operatorId` 从 Sa-Token `StpUtil.getLoginId()` 服务端获取，忽略请求体（防伪造）
- regenerate 60 秒冷却防滥用（429）

## 5. CI 强制 Docker 测试

`.github/workflows/ci.yml` + 父 `pom.xml` 的 `ci-enforce-docker-tests` profile：

- `GITHUB_ACTIONS=true` 时 enforcer 强制 `PULSEFLOW_TEST_DOCKER=true`，否则 **构建失败**——Docker 集成测试不允许被静默跳过
- 用 `GITHUB_ACTIONS` 而非 `CI` 变量（本地 IDE 会设 `CI=true` 误触发）
- GitHub Runner 上真实拉 `mysql:8.0` 镜像，11 个 IT 零跳过零失败
- 同一 workflow 静态运行 `python testing/functional/validate_ai_dataset.py`，校验 JSONL、manifest/SHA-256、case 数量、ID、类别和必需字段；不启动 PulseFlow 或外部 AI Provider

CI 首次真实跑通时修复了 4 个仅 CI 环境暴露的潜伏 bug：

1. `flyway-mysql` 版本与 `flyway-core` 不对齐 → `AbstractMethodError`
2. 测试配置注解 `@TestConfiguration` 非 `@SpringBootConfiguration` → Spring Boot 找不到配置源
3. Redisson 改名 `RedissonAutoConfigurationV2` → `@EnableAutoConfiguration(exclude=...)` 链不收敛
4. Flyway 索引改名后 `FlywayMigrationIT` 仍校验旧索引名

## 6. 已知限制

- `cartWithoutPurchaseRate` 是代理指标（活跃但未消费），非真正的加购未购率
- baseline 使用候选池（`LIMIT 50000`）而非全站数据
- `unsubscribeCount` v1 固定为 0，schema 未追踪退订
- `variantMetrics` v1 固定为空数组，不支持 A/B 变体分析
- Testcontainers 已固定为 1.21.4，兼容 Docker Desktop 29.x 的最低 API 1.44 要求；
  本地 IT 仍默认跳过，设置 `PULSEFLOW_TEST_DOCKER=true` 后可完整执行

## 7. 手工专项测试边界

`testing/` 保留确定性数据生成、HTTP Functional Replay、最终状态 Validator、受控并发、Campaign/Attribution 验证、真实 AI/API Evaluation 和 k6 Smoke/Load/Stress。它们需要本地运行中的应用或隔离中间件，属于开发者主动执行的专项验收，不重复普通 push/PR CI。
