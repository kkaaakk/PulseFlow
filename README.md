# PulseFlow

PulseFlow 是事件驱动的用户画像与 Campaign 平台。Java 服务负责权威数据、规则决策、频控、触达、归因，以及确定性 Campaign 规划能力。

## 业务链路

```text
Event API → Kafka → MySQL 事件事实 → Profile (Redis / MySQL)
          → Campaign Selection → Frequency Control → Delivery
          → Click / Conversion → Last-Touch Attribution
          → Campaign Performance Summary
```

`pulseflow-campaign` 还提供 Campaign DSL 校验、字段白名单、规则转换、人群预估、草稿保存/编辑/确认、内容事实校验和绩效计算。草稿确认后创建状态为 `DRAFT` 的 Campaign，后续执行仍由原有规则与触达链路负责。

## Agent 接入边界

Python + Pydantic AI Agent Service 通过 Java 的内部只读业务 Tool API 自主调查、记录 Evidence、Hypothesis、Diagnosis 并持久化 CampaignProposal。类型化 Campaign Proposal 与人工确认由 Java 的登录归属、DSL 校验和草稿服务控制。Agent owns reasoning. Java owns deterministic business writes. 完整的组件与调用边界见 [架构文档](ARCHITECTURE.md)。

**Phase 6/7 链路为 Evidence-backed Proposal → 用户点击生成 Java Draft → 人工确认。** Agent 运行方法见 [Agent README](pulseflow-agent/README.md)，评测与 PII 边界见 [架构文档](ARCHITECTURE.md)。Java 不包含 LLM Provider、Prompt Runtime、模型输出解析器或 AI Review。

```text
Python Growth Investigation Agent
          │ 已鉴权的只读 HTTP Tool Calls
          ▼
PulseFlow Java Internal Agent Tool API
  ├─ 指标查询 / 对比 / 拆解
  ├─ Campaign Performance Summary
  ├─ Attribution Breakdown
  └─ Campaign DSL Validation / Audience Preview

Agent → 持久化 CampaignProposal → 停止
Java 登录用户 → 生成 Draft → 人工确认草稿 → Campaign / CampaignRule（DRAFT）
```

## 模块

| 模块 | 职责 |
|---|---|
| `pulseflow-common` | 公共实体、Mapper、DTO 与工具 |
| `pulseflow-event` | 行为接入、Kafka 消费、幂等持久化 |
| `pulseflow-profile` | 实时、窗口和长期用户画像 |
| `pulseflow-campaign` | Campaign 规划、校验、决策、频控、触达、归因、分析 |
| `pulseflow-job` | 窗口指标、标签、Campaign 扫描、绩效汇总和恢复任务 |
| `pulseflow-simulator` | 演示数据模拟 |
| `pulseflow-boot` | Spring Boot 启动、Web 查询、鉴权与 Flyway |

## 启动项目（PowerShell）

需要运行中的 Docker Desktop（含 Compose）。从仓库根目录执行以下命令，缺少镜像时自动构建，前后端及所有依赖一起启动；修改源码后使用 `.\start.ps1 -Build` 重建：

```powershell
.\start.ps1
```

项目容器不会随 Docker Desktop 或电脑重启自动启动；需要使用时手动运行 `.\start.ps1`。该设置不影响 Docker Desktop 自身的开机启动。

脚本首次创建被 Git 忽略的根目录 `.env`，复用 `pulseflow-agent/.env` 中已有的模型及 Azure PII 配置，并生成数据库口令和内部机器令牌。没有外部凭据时，先补齐根目录 `.env` 再启动；正常运行要求真实模型，缺配置会明确报错。

默认启用 Redis 会话、Agent 内部工具与调查链路、DeepSeek 真实模型及 Azure PII、Java/Python Trace 导出、已鉴权的模拟事件接口。XXL-JOB 调度中心和数据库随栈启动，10 个业务 Job 自动登记并启用。前端使用真实 API；开发免密登录和前端模拟数据保持显式开发选项。

| 入口 | 地址 |
|---|---|
| Web 控制台 | http://127.0.0.1:5173 |
| Java API | http://127.0.0.1:8080 |
| XXL-JOB 调度中心 | http://127.0.0.1:8081/xxl-job-admin |
| Jaeger Trace 查询 | http://127.0.0.1:16687 |

控制台登录使用 Operator ID `1024`、密码 `pulseflow-local`（可在根目录 `.env` 修改）。XXL-JOB 用户名 `admin`，密码见 `.env` 的 `XXL_JOB_ADMIN_PASSWORD`。数据库、Redis、Kafka 和任务日志使用独立 Docker 卷；完整启动、任务频率和验证命令见 [Docker 运行说明](deploy/README.md)。

停止服务并保留数据库卷：

```powershell
docker compose down
```

离线 CI/功能测试仍使用独立的测试栈，需显式选择：

```powershell
$env:PULSEFLOW_AGENT_INTERNAL_TOKEN = 'pulseflow-local-machine-token'
docker compose -f .\testing\docker-compose.test.yml --profile agent up --build -d
```

分别在本机运行 Java 或 Python 服务的命令与配置见 [Web README](pulseflow-web/README.md) 和 [Agent README](pulseflow-agent/README.md)。

## 本地验证

源码验证需要 JDK 17、Maven、Node.js。正常运行的 MySQL、Kafka、Redis、XXL-JOB 和 Trace 采集端由根目录 Docker 栈提供；应用默认监听 8080。

```powershell
cd pulseflow
mvn clean test
mvn clean verify
```

```powershell
cd pulseflow-web
npm ci
npm run typecheck
npm run test
npm run build
npm run test:e2e:demo
```

`mvn clean verify` 中的 Docker 集成测试在 `PULSEFLOW_TEST_DOCKER=true` 时运行，CI 强制设置该变量。Flyway V1–V5 是历史迁移，V6 引入调查归属，V7 将 Draft 与 Investigation 关联并清理旧授权表；`campaign_ai_draft` 承载 Campaign 草稿，`campaign_performance_summary` 承载绩效。

前端控制台见 [Web README](pulseflow-web/README.md)；系统组成、业务链路与指标边界见 [架构文档](ARCHITECTURE.md)。

## 调查前端与运行加固

Phase 7 adds the authenticated **Investigations** workspace, safe business SSE, cancellation/recovery,
draft review with human confirmation, bounded runtime controls and a complete default Docker
stack. See [the architecture](ARCHITECTURE.md) for component boundaries and single-process deployment
requirements; see [the Agent README](pulseflow-agent/README.md) for startup and validation.
