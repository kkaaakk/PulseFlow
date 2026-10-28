# PulseFlow 架构

PulseFlow 是 Monorepo 中的三层全栈系统：`pulseflow-web` 提供运营交互，`pulseflow-agent` 负责基于证据的调查与方案建议，`pulseflow` Java 服务持有权威业务数据并执行确定性业务操作。`testing` 验证跨模块链路，`.github/workflows` 执行自动回归。

```text
PulseFlow/
├── pulseflow/                 # Java 核心业务后端，Maven 多模块
├── pulseflow-agent/           # Python 3.11 + FastAPI + Pydantic AI
├── pulseflow-web/             # Vue 3 + TypeScript + Vite 管理台
├── testing/                   # 系统功能、性能测试与测试数据
├── .github/workflows/         # GitHub Actions CI
├── README.md                  # 项目入口与本地验证
└── ARCHITECTURE.md            # 本文档
```

## 系统边界与调用关系

```text
运营人员
   │
   ▼
pulseflow-web (Vue)
   │ HTTP / 已登录用户会话
   ▼
pulseflow (Java Web API / Sa-Token 网关) ◄── 内部只读 Tool API ── pulseflow-agent
   │               │                                          ▲
   │               └── 调查、Proposal 网关 ──────────────────────┘
   │
   ├── MySQL：事件事实、画像、Campaign、触达、归因、绩效与草稿
   ├── Redis：实时画像、延迟队列、频控与处理标记
   ├── Kafka：事件与触达消息
   └── XXL-JOB：聚合、标签、Campaign 扫描和恢复任务

pulseflow-agent ── 独立 Agent DB：Investigation、Evidence、Hypothesis、
                    Diagnosis、消息和 CampaignProposal
```

浏览器访问 Java 的普通 `/api/**` 接口；调查页面也经过 Java 鉴权与归属检查，再由 Java 网关调用 Agent。Agent 通过受机器令牌保护的 Java Tool API 获取聚合事实，不直连业务数据库，也不能自行确认、激活或触达 Campaign。**Agent 决定查什么和如何分析；Java 决定数据是否真实、业务写入是否合法。**

原有分散在 `docs/` 和根目录的阶段报告、设计草稿与架构说明已收敛为本文档；演示用 SQL 是测试数据，位于 `testing/data/fixtures/seed-demo-data.sql`。

## `pulseflow/`：Java 权威业务系统

`pulseflow/pom.xml` 管理以下 7 个 Maven 模块，`Dockerfile` 用于服务镜像构建。

| 模块 | 职责 |
| --- | --- |
| `pulseflow-common` | 公共实体、Mapper、DTO 和工具类。 |
| `pulseflow-event` | 用户行为接入、Kafka 消费、事件事实幂等落库。 |
| `pulseflow-profile` | Redis 实时画像、窗口指标和长期标签的统一查询。 |
| `pulseflow-campaign` | Campaign DSL、规则决策、频控、触达、归因、草稿、人群预估与绩效。 |
| `pulseflow-job` | XXL-JOB 后台任务：指标聚合、标签计算、Campaign 扫描、重试与恢复。 |
| `pulseflow-simulator` | 模拟用户行为和演示数据。 |
| `pulseflow-boot` | Spring Boot 入口、Controller、配置、鉴权、Java ↔ Agent 网关和内部 Tool API。 |

`pulseflow-boot/src/main/java/com/pulseflow/boot/` 是应用入口层：

```text
boot/
├── PulseFlowApplication.java        # Spring Boot 启动类
├── config/                          # Kafka、Redis、XXL-JOB、Sa-Token 等配置
│   ├── KafkaConfig.java
│   ├── RedisConfig.java
│   ├── XxlJobConfig.java
│   └── SaTokenConfig.java
├── web/                             # 普通前端使用的 Web API
└── agenttools/                      # Agent Tool API 与 Proposal/Draft 网关
    ├── AgentToolController.java
    ├── AgentToolService.java
    ├── AgentMetricService.java
    ├── AgentProposalGateway.java
    ├── AgentToolAuthFilter.java
    └── CampaignProposalDraftService.java
```

### 业务主链路

```text
Event API → Kafka → MySQL 事件事实 → Profile (Redis / MySQL)
          → Campaign Selection → Frequency Control → Delivery
          → Click / Conversion → Last-Touch Attribution
          → Campaign Performance Summary
```

1. **事件与幂等**：事件以 `userId` 为 Kafka Key；消费先写 `user_event` 和小时指标，再通过 Redis Lua 更新实时画像，最后执行 Campaign 决策。`eventId` 在 MySQL 中唯一；重复投递会读取已落库的标准事件，不能只因数据库重复就跳过 Redis 和决策。Redis 的 `event:processed:{eventId}` 防止指标重复累计；失败时 `EVENT_REPLAY` 补偿任务重做未完成阶段。
2. **三级画像**：实时状态在 Redis 的 `user:rt`、`user:daily`、`user:cart`；小时事实聚合到 `user_metric_daily`、`user_behavior_summary` 等窗口指标；`TagRecalcJob` 计算 `user_tag` 长期标签。上层通过 Profile 服务统一查询。
3. **三种 Campaign 触发**：`EVENT` 由事件即时决策；`DELAYED` 由 Redis ZSET 到期后复核；`SCHEDULED` 由 `CampaignSelectionJob` 建立 `campaign_execution`，分页圈选用户。决策只创建 `delivery_task`，不直接发送消息。
4. **触达与频控**：`delivery_task` 的业务 `dedup_key` 防重复创建。Kafka 投递失败由 `DispatchRetryJob` 重发；消费者抢占 `PENDING → PROCESSING`。Redis Lua 原子检查用户日频控、Campaign 周频控并写 `freq:reserved:{taskId}`，同一任务重试只占一次额度。站内信与模拟 Push 还使用业务唯一键兜底；失败任务由恢复 Job 处理。
5. **点击与转化归因**：`ORDER_PAID` 等目标事件进入归因等待队列，宽限窗口后选择归因窗口内最近的有效点击，写入 `attribution_record`。当前模型为 `CLICK_LAST_TOUCH`，目标事件唯一约束阻止重复归因。

Campaign DSL 由 Java 验证字段、类型和操作符；人群预估由 Java 根据画像快照计算。`PerformanceSummaryCalculator` 根据 Delivery、Click、Attribution 事实生成 `campaign_performance_summary`，Job 负责调度。Campaign 草稿确认后创建状态为 `DRAFT` 的 Campaign，后续执行仍走既有规则和触达链路。

业务库的 Flyway V1–V5 是历史迁移；V6 引入调查归属，V7 把 Draft 与 Investigation 关联并清理旧授权表。`campaign_ai_draft` 承载草稿，`campaign_performance_summary` 承载绩效摘要；Agent 自己的迁移和数据库与之分离。

## `pulseflow-agent/`：Python Agent 服务

```text
pulseflow-agent/
├── pyproject.toml                 # Python 依赖
├── .env.example                   # 环境变量示例
├── Dockerfile
├── alembic.ini
├── migrations/versions/          # 独立 Agent DB 迁移
│   ├── 0001_agent_workspace.py
│   ├── 0002_campaign_proposals.py
│   └── 0003_proposal_boundary.py
├── src/pulseflow_agent/
│   ├── main.py                    # FastAPI / HTTP 入口
│   ├── runtime.py                 # Agent Runtime 与运行限制
│   ├── config.py                  # 配置
│   ├── agent/
│   │   ├── growth_investigator.py # Growth Investigation Agent
│   │   ├── dependencies.py        # Tool 与运行依赖
│   │   ├── instructions.py        # Agent 指令
│   │   └── observations.py        # Java 结果转为观察与 Evidence
│   ├── domain/
│   │   ├── investigation.py       # Investigation、Hypothesis、Evidence、Diagnosis
│   │   ├── campaign_proposal.py   # CampaignProposal
│   │   └── contracts.py           # Tool 和业务类型契约
│   ├── clients/pulseflow_api.py   # Python → Java Tool HTTP 客户端
│   ├── workspace/
│   │   ├── models.py
│   │   ├── repository.py
│   │   └── service.py             # 调查与 Proposal 的持久化
│   ├── security/pii_guardrail.py  # PII 出站边界
│   └── observability/tracing.py   # 安全追踪
└── tests/                         # 单元、集成和离线评测
```

`agent/` 是调查与推理逻辑；`domain/` 定义强类型的 `Investigation`、`Evidence`、`Hypothesis`、`Diagnosis`、`CampaignProposal`；`workspace/` 保存调查过程、消息和方案，支持同 ID 续查。Agent 有独立数据库和 Alembic 迁移，不拥有 Java 业务库的凭证。

Agent 的六类 Java 只读 Tool 是指标查询、同期对比、维度拆解、Campaign 绩效、归因拆解、人群预估。Java 只接受受限维度与聚合查询，返回可关联 Evidence 的 `queryId`、`dataVersion` 等元数据；Tool 响应不含单用户明细、原始事件或 SQL。`X-PulseFlow-Agent-Token` 是服务间凭证，不能进入浏览器；部署入口应阻断公网访问 `/internal/**`。

真实模型模式会在出站前检查本地敏感字段和自然语言 PII；PII 检测故障时阻断模型调用。Agent 记录安全的状态与追踪信息，不记录 Prompt、Token、原始 Tool 数据或凭证。离线 `TestModel` / `FunctionModel` 用于无付费请求的 CI 评测；真实模型评测单独启用。运行时有请求、Tool、Token 和期限限制；成本配置目前不是硬性计费上限。

### 调查到 Campaign 的人工确认边界

```text
登录用户提出问题
  → Java 校验身份与 Investigation 归属
  → Agent 调 Java 只读 Tool，保存 Evidence / Hypothesis / Diagnosis
  → Agent 保存有证据支撑的 CampaignProposal，停止
  → 用户点击“生成 Campaign Draft”
  → Java 读取已保存 Proposal，检查归属、Evidence、DSL、人群与优惠事实
  → Java 创建 Draft（按 Proposal ID 幂等）
  → 用户审阅并单独确认
  → Java 创建 DRAFT 状态的 Campaign / CampaignRule
```

前端的调查、续查、取消和业务 SSE 均走 Java 网关。SSE 只展示业务进度，已持久化的 Investigation 快照是权威状态。Agent 的 Proposal 不是 Campaign；Agent 不调用 Draft 确认、激活或触达写接口。Java 与 Agent 数据库之间没有分布式事务；Draft 已提交但 Proposal 状态回写失败时，重试先读取幂等 Draft 再修复状态。当前 Agent 的准入与取消状态在单进程内，部署采用单 Uvicorn worker、单 Agent 副本对应一个 Agent schema。

## `pulseflow-web/`：Vue 运营管理台

```text
pulseflow-web/
├── package.json
├── vite.config.ts
├── index.html
├── src/
│   ├── main.ts, App.vue
│   ├── api/            # 调 Java Web API 的 HTTP 客户端
│   │   ├── investigations.ts
│   │   ├── campaigns.ts
│   │   ├── events.ts
│   │   ├── attribution.ts
│   │   ├── deliveries.ts
│   │   └── dashboard.ts
│   ├── components/     # 公共组件
│   ├── layouts/        # 页面布局
│   ├── router/         # Vue Router
│   ├── stores/         # Pinia 状态
│   ├── types/          # TypeScript 类型
│   ├── styles/         # CSS
│   └── views/          # Dashboard、Campaigns、Investigations 等业务页面
└── e2e/                # Playwright 前端端到端测试
```

`views → components → api/*.ts → HTTP → Java` 是常规业务页面路径。`src/api/investigations.ts` 负责新 Agent Investigation 页面与 Java 网关通信；`campaigns.ts`、`events.ts`、`attribution.ts`、`deliveries.ts`、`dashboard.ts` 对应原有 Java 业务能力。浏览器不持有内部 Agent 令牌。

## `testing/`：项目级验证

```text
testing/
├── functional/             # 数据生成、HTTP Replay、最终业务状态 Validator
│   ├── generate.py
│   ├── replay.py
│   ├── validate.py
│   └── run.ps1
├── performance/            # k6 Smoke / Load / Stress
│   ├── performance.js
│   └── run.ps1
├── data/                   # 固定 fixture、运行时生成数据、演示 SQL
│   └── fixtures/seed-demo-data.sql
├── reports/                # 运行时报告（忽略提交）
├── docker-compose.test.yml # 隔离测试依赖及可选 Agent Compose profile
├── run-all.ps1             # Functional / Performance 编排入口
├── common.ps1              # 公共测试脚本
└── agent_smoke.py           # 离线 Java ↔ Agent smoke 验证
```

Java 模块和 Python 服务各自保留单元、集成测试；`testing/` 验证整个系统启动后的跨模块结果。例如 Attribution、Redis processing、Kafka、数据库提交和 `freq:reserved`，需要看 Replay 后的最终业务状态，而不只看单个类。Functional 侧重事件重放、幂等、乱序、并发、Campaign、触达、归因和补偿；Performance 侧重吞吐、错误率和延迟。手工专项入口及环境要求见 [testing README](testing/README.md)。

## `.github/`：持续集成

`.github/workflows/ci.yml` 当前执行：

- Java：JDK 17、`mvn clean verify`；CI 设定 `PULSEFLOW_TEST_DOCKER=true` 运行 Docker 集成测试。
- Python Agent：`uv sync --locked`、`ruff check`、`mypy`、`pytest`，并在离线 Compose 栈中运行 Java ↔ Agent smoke 测试。
- Vue：`npm ci`、`npm run typecheck`、`npm run lint`、`npm run test`、`npm run build`。

前端 Playwright E2E 由 `pulseflow-web/e2e/` 提供，可按 [Web README](pulseflow-web/README.md) 在本地运行；当前 CI 工作流没有调用它。`testing/` 的完整 Functional Replay 与 k6 压测也是主动执行的专项验收。

本地可在 `pulseflow/` 执行 `mvn clean test` 或 `mvn clean verify`；在 `pulseflow-web/` 执行 `npm run typecheck`、`npm run test`、`npm run build`、`npm run test:e2e`。`testing/run-all.ps1` 串联手工 Functional 与 Performance 验证。

## 默认 Docker 运行栈

根目录 `compose.yml` 和 `start.ps1` 是完整运行入口。Web/Nginx、Java、Agent、业务 MySQL、独立 Agent MySQL、Redis、Kafka、XXL-JOB Admin/独立数据库、Jaeger 一起启动。依赖通过健康检查排序；Agent 的 Alembic 迁移与 XXL-JOB 的任务登记分别由一次性容器完成。

Java 默认采用 Redis 会话；Docker 中启用真实 Agent、Azure PII、Java/Python OTLP 导出及需要登录的模拟事件接口。内部机器令牌和数据库密码首次启动生成，模型凭据复用本地配置。Python 使用生产配置校验及一个 worker，不自动退回离线模型。XXL-JOB 的 10 个业务任务首次登记即开启，后续启动保留用户调整过的周期和暂停状态。任务执行器显式宣告容器网络地址 `http://java:9999/`。

完整运行栈和 `testing/docker-compose.test.yml` 使用不同项目名、数据卷及数据库端口；测试栈继续承载离线 CI。部署入口、任务周期和验证方式见 [Docker 运行说明](deploy/README.md)。

## 业务指标口径

Java 的 `AudienceMetricsAggregator` 与 `PerformanceSummaryCalculator` 是人群和 Campaign 绩效的权威计算器。比率用 0～1 的 `BigDecimal` 表示，保留 4 位小数并 `HALF_UP`；金额和均值保留 2 位；分母为 0 时比率返回 0。人群预估提供画像数据版本，绩效摘要提供计算时间。

绩效基础值：`sentCount` 来自 `delivery_record` 条数，`deliveredCount` 来自其中成功状态记录，`clickedCount` 来自去重点击用户，`convertedCount` 来自去重归因用户。`deliveryRate = deliveredCount / sentCount`，`clickRate = clickedCount / deliveredCount`，`conversionRate = convertedCount / clickedCount`。Agent 的时间窗聚合按各类事实自己的时间字段过滤，同窗比率不能解释为同一批用户的因果转化率；跨维度分组求和也可能重复计入同一用户。人群洞察 v1 的基线是最多 50,000 个活跃画像用户构成的候选池，不代表真实全站。

## 源码阅读顺序

建议沿业务数据流阅读：`pulseflow-event → pulseflow-profile → pulseflow-campaign → pulseflow-job → pulseflow-boot → pulseflow-agent → pulseflow-web`。先理解事实如何产生与落库，再看 Java 如何决策和执行，最后看 Agent 如何通过受限 Tool 使用这些事实，以及前端如何呈现和触发人工确认。
