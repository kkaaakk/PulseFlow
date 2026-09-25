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

Python + Pydantic AI Agent Service 已在 Java 服务外建立基础，并通过 [内部业务 Tool API](docs/agent/agent-tool-contract.md) 自主调查、记录 Evidence 和生成 Diagnosis。调查契约见 [Investigation Domain](docs/agent/investigation-domain.md)。后续阶段将实现 workspace 持久化与类型化 Campaign Proposal；Java 负责重新验证 DSL、人群预估、草稿归属与人工确认。

**Phase 3 已将单个 Python Agent 接到 Java 只读 Tool API。** 当前提供内部调查入口，尚未连接前端，也没有跨请求持久化。运行方法见 [Agent README](pulseflow-agent/README.md)。Java 不包含 LLM Provider、Prompt Runtime、模型输出解析器或 AI Review。PII 要求见 [PII Guardrail Contract](docs/agent/pii-guardrail-contract.md)。

```text
Python Growth Investigation Agent
          │ 已鉴权的只读 HTTP Tool Calls
          ▼
PulseFlow Java Internal Agent Tool API
  ├─ 指标查询 / 对比 / 拆解
  ├─ Campaign Performance Summary
  ├─ Attribution Breakdown
  └─ Campaign DSL Validation / Audience Preview
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

## 本地验证

需要 JDK 17、Maven、Node.js。运行中的业务链路需要 MySQL 8、Kafka、Redis，XXL-JOB Admin 独立部署在 8081；应用默认监听 8080。

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

`mvn clean verify` 中的 Docker 集成测试在 `PULSEFLOW_TEST_DOCKER=true` 时运行，CI 强制设置该变量。Flyway V1–V5 迁移保持原样；历史 `campaign_ai_draft` 表仍承载 Campaign 草稿，`campaign_performance_summary` 仍承载绩效，旧生成/复盘表只作为历史 schema 保留。

前端控制台位于 [`pulseflow-web`](pulseflow-web/README.md)。运行设计见 [pulseflow-design.md](pulseflow-design.md)，指标口径见 [Campaign Metrics Glossary](docs/campaign-metrics-glossary.md)，本次迁移见 [Preparation Report](docs/agent/pydantic-ai-preparation-report.md)。
