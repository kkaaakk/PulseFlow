# PulseFlow 数据验收测试第一阶段报告

## 中文说明

本报告记录第一阶段测试基础设施的新增内容、实际执行结果和发现的问题。
当前环境已完成源码契约检查、数据生成器、AI 数据集覆盖、Maven 基线和工具安全性
验证；由于 Docker daemon、PulseFlow 应用和 k6 不可用，真实 HTTP Replay、MySQL/
Redis 校验、Smoke、Campaign、Attribution 及 AI API 评估均明确记录为 `NOT_RUN`，
没有将环境缺失伪装成通过。下一阶段只应根据“下一阶段建议修复项”处理，
不要在本阶段自动修改业务逻辑。

Git 分支：`main`
测试工作开始前的 Commit：`1c1c0b11b8fd4b72e7ab5095ef3330b0b6e53f85`
Base SHA：`1c1c0b11b8fd4b72e7ab5095ef3330b0b6e53f85`
Final SHA：尚未提交，修改仍在工作区。

## 新增测试基础设施

- `testing/generator/generate_dataset.py`：支持 seed 的 SMALL/MEDIUM/LARGE 生成器；
- 确定性 JSONL、Manifest 和 SHA-256 输出；
- NORMAL、duplicate、out-of-order、late、invalid、hot-user、campaign/frequency/attribution 场景；
- 按顺序执行的 HTTP Replay、逐行结果和失败证据；
- 使用真实 Flyway 表名和 Key 名的 MySQL/Redis Validator；
- 隔离的 `testing/docker-compose.test.yml`，提供 MySQL、Redis、Kafka；
- k6 Smoke、Load 和手动 Stress 脚本；
- AI 语义数据集及离线/API Evaluator；
- PowerShell 入口、测试目标安全检查、契约漂移检查、Campaign Fixture 准备脚本和 ADR。

## 数据集生成器

使用 seed `20260827` 生成 SMALL 的 `all` 场景已成功完成：

| 数据集 | 输入行数 | 唯一 event id 数 | SHA-256 |
|---|---:|---:|---|
| normal-events-small-v1 | 10000 | 10000 | `9b8401813aa9bc4e1fe6b8adebbfb116da2629ade2b9335990ecba53c095b61d` |
| duplicate-events-v1 | 129 | 100 | `5d369c10cc35f406d67e63927ddecc3cc367341cec29c5d3ebb2a367da10c7cd` |
| out-of-order-events-v1 | 6 | 6 | `09419991fca79978c7eba9b3d4eba64b3cc6bbf7d5b3ff8a3ab6a42a8769034b` |
| late-events-v1 | 6 | 6 | `8d2d65867a730db22ae8c856eff989e65687618b8637b74fa8f20d72276beee4` |
| invalid-payload-v1 | 13 cases | 0 | `bfa2bc9a4a97732de6f86274b026bec0709f3c3bbebbe01f21eac376809bf1f5` |
| hot-user-events-small-v1 | 1000 | 1000 | `de8231f9c777ce86cb1d4499e47681de319ba4bf26ef5c09e5db10fea8b9b6e4` |
| campaign-frequency-attribution-v1 | 7 | 7 | `3cfd4b7599df7833b817a47d444f820df4bded4bf8f34b748f8aa06305110e73` |

同一个 normal 数据集连续生成两次，得到的 SHA-256 一致。生成的大数据文件已被 Git
忽略。

## 数据集场景

提示中要求的边界场景均已表示。Duplicate Manifest 将第一次出现的 Payload 作为 MySQL
canonical row；late-event Manifest 记录源码的整分钟严格 skew 规则。`-RebaseEventTime`
可以在较晚日期 Replay 时保留 Fixture 的相对顺序和边界。

已提交的 `smoke-events-v1` Fixture 有独立 checksum 和精确指标总量。由于当前环境没有
真实跑出 PulseFlow 失败，Regression Fixture 目录仍按设计保持为空。

## k6 测试

k6 脚本已通过 Node 语法检查，使用源码定义的 EventType、真实 `EventRequest` 结构、
HTTP/ApiResponse 断言、请求节奏控制，以及兼容 p50/p95/p99 的 k6 指标。本机没有安装
k6，因此 Smoke/Load/Stress 均为 **NOT RUN**。Stress 只能手动执行，并要求
`ALLOW_STRESS=true`。

## Validator

`verify_contract.py` 的 12 项源码契约检查全部通过。AI 数据集覆盖检查包含 13 个
用例/类别并通过。Validator 安全行为已实际验证：

- 非回环 Replay 目标：拒绝执行，退出码 2；
- 非测试数据库名：拒绝执行，退出码 2；
- MySQL/Redis 不可用：报告 `NOT_RUN`，退出码 2；
- 显式跳过 k6：退出码 2 并给出警告，不会报告 PASS。

## 集成测试

本阶段没有重复已有 Maven 测试。`mvn -q clean verify` 已以退出码 0 完成：

- Surefire：104 个测试，0 failures，0 errors，0 skipped；
- Failsafe：收集 15 个用例，0 failures，0 errors，其中 5 个因
  `PULSEFLOW_TEST_DOCKER` 跳过，实际执行 10 个；
- 因此本机没有执行 Docker Testcontainers 覆盖。

Compose YAML 解析成功，但 Docker daemon 不可用：`dockerDesktopLinuxEngine` 没有运行。
本报告没有宣称真实 PulseFlow HTTP Replay、MySQL Validator、Redis Validator、k6、
Campaign Fixture 或全链路 SMALL 验收成功。

## 执行命令

```powershell
.\testing\scripts\run-baseline.ps1
.\testing\scripts\generate-dataset.ps1 small
.\testing\scripts\run-full-validation.ps1 -PrepareDependencies
.\testing\scripts\run-edge-validation.ps1
.\testing\scripts\run-campaign-validation.ps1
.\testing\scripts\evaluate-ai.ps1
```

## 测试结果

| 阶段 | 结果 | 证据 |
|---|---|---|
| 已有单测/Build 基线 | PASS | `mvn -q clean verify`，Surefire 104/104 |
| 已有 Docker IT | NOT RUN | Docker daemon 不可用，5 个 Failsafe 用例跳过 |
| 源码契约 | PASS | 12/12 项检查 |
| 生成器/确定性 | PASS | 相同 seed 的 checksum 一致 |
| AI 数据集覆盖 | PASS | 13 个用例 |
| Smoke | NOT RUN | 未安装 k6，应用未运行 |
| Duplicate | NOT RUN | 应用/DB/Kafka/Redis 不可用 |
| Out-of-order | NOT RUN | 应用/DB/Kafka/Redis 不可用 |
| Late event | NOT RUN | 应用/DB/Kafka/Redis 不可用 |
| Invalid payload | NOT RUN | 应用未运行，只做了 dry-run |
| Campaign frequency | NOT RUN | 应用/DB/Kafka/Redis 不可用 |
| Attribution | NOT RUN | 应用/DB/Kafka/Redis 不可用 |
| AI API 评估 | NOT RUN | 没有运行中的认证应用，离线覆盖检查已通过 |

## 发现的问题

P1:

- 原始 `CLICK` 输入会持久化为 `user_event`，但当前 raw-event consumer 不会调用
  `ClickEventService`，仅通过 HTTP Replay 无法产生用于 Last-Touch Attribution 的
  `click_event` 行。

P2:

- Ingress 在 HTTP 层接受 unknown EventType、负数/溢出金额、非 UUID event id 和超长
  event id；下游行为留给 invalid 数据集暴露，当前阶段不修复。
- `Duration.toMinutes() > 5` 使迟到 5 分钟加 1 毫秒的事件不会立即标记为 skew，必须
  再超过完整 1 秒才会被标记。
- `docs/demo/demo-scenario.md` 使用了源码 `EventType` 枚举不存在的 `CAMPAIGN_CLICK`；
  源码有效 EventType 是 `CLICK`。
- 仓库文档报告 109 个测试/11 个 IT，而当前 clean 构建报告 104 个 Surefire 测试和
  15 个 Failsafe 用例，其中 5 个由 Docker gate 跳过；后续需要统一统计口径。

P3:

- 本机全链路执行需要先启动 Docker Desktop 并安装 k6；当前测试工具会明确报告这些条件。

## 是否修改业务代码

**NO。** 本阶段没有修改生产 Java、SQL migration、运行时配置或业务逻辑。用户之前对
`pulseflow/pom.xml` 的修改已原样保留。

## 下一阶段建议修复项

- 确定 raw `CLICK` 如何写入 `click_event`，或提供专用 click API，再把 HTTP-only
  Attribution Replay 视为完整端到端通过；
- 定义并实施 EventType、event id 长度/格式、金额范围和 malformed JSON 状态码等
  接入约束；
- 统一 `Duration` 精度和 late-event 策略；
- 更新过期的 demo EventType 和测试数量文档；
- 在 Docker、Kafka、Redis、MySQL、应用和 k6 均可用时，重新执行 SMALL、边界、Campaign
  和 AI API 套件；真实失败保留到 `testing/datasets/fixtures/regressions/`。
