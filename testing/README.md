# PulseFlow Testing

`testing/` 是开发者主动执行的专项验收工具集，不重复 GitHub Actions 的快速回归。它保留完整业务 Replay、最终状态 Validator、受控并发、Campaign/Attribution 验证和 k6 性能测试。

## 职责边界

### GitHub CI 自动负责

- Backend：在 `pulseflow/` 执行 `mvn clean verify`，覆盖 Unit、Integration、Testcontainers 和 Flyway Migration。
- Frontend：在 `pulseflow-web/` 执行 `npm ci`、`npm run typecheck`、`npm run lint`、`npm run test` 和 `npm run build`。
- AI Dataset：在仓库根目录执行 `python testing/functional/validate_ai_dataset.py`，检查 JSONL、manifest/SHA-256、数量、ID、类别和字段完整性。

这些 CI 检查不启动 PulseFlow，也不需要 Kafka、MySQL、Redis、Azure、AI Provider 或 Secret。

### `testing/` 手工专项负责

- Functional Replay 与最终业务状态 Validator；
- Deterministic Dataset Generator、重放证据和受控并发正确性；
- Campaign、Frequency Control、Delivery、Attribution、Compensation 验证；
- 真实运行中的 PulseFlow AI Campaign API Evaluation；
- k6 Smoke、Load、Stress 性能测试。

Maven/JUnit 测试继续留在 `pulseflow/**/src/test`。本地需要 Maven 验证时直接执行：

```powershell
cd pulseflow
mvn clean verify
```

## 目录

```text
testing/
├── README.md
├── .env.test.example
├── .gitignore
├── common.ps1
├── docker-compose.test.yml
├── run-all.ps1
├── functional/
│   ├── generate.py
│   ├── replay.py
│   ├── validate.py
│   ├── validate_ai_dataset.py
│   ├── evaluate_ai.py
│   ├── ownership.json       # Functional 测试所有权目录
│   ├── state.py             # MySQL/Redis pre-clean、post-clean、核对
│   ├── campaign-fixture.sql
│   └── run.ps1
├── performance/
│   ├── event.js
│   ├── performance.js
│   └── run.ps1
├── data/
│   ├── ai/
│   ├── fixtures/
│   └── generated/          # 运行时生成，已忽略
└── reports/                # 运行时报告，已忽略
```

架构边界决策见 [`docs/adr/ADR-004-functional-replay-and-performance-boundaries.md`](../docs/adr/ADR-004-functional-replay-and-performance-boundaries.md)。

## 前置条件

Functional 需要运行中的 PulseFlow、Kafka、MySQL 和 Redis。可以先启动隔离依赖：

```powershell
docker compose -f .\testing\docker-compose.test.yml -p pulseflow-test up -d
```

应用默认连接测试数据库 `pulseflow_test`、MySQL `13306`、Redis `16379` 和 Kafka `19092`；完整环境变量见 [`.env.test.example`](.env.test.example)。脚本默认只允许 loopback URL 和测试数据库。

## Functional Replay

```powershell
.\testing\functional\run.ps1 `
  -Scale small `
  -BaseUrl http://localhost:18080 `
  -RebaseEventTime
```

默认覆盖 fixture、normal、duplicate、out-of-order、late、invalid、hot-user、concurrency 和 campaign。单独运行时可使用 `-Scenario normal`、`-Scenario edge`、`-Scenario concurrency` 或 `-Scenario campaign`。

`generate.py` 使用 fixed seed 生成 normal、duplicate、out-of-order、late、invalid、hot-user、concurrency 和 campaign 数据；`replay.py` 负责 HTTP Replay、到达顺序、受控并发、事件时间 rebase、dry run、失败记录和 replay evidence；`validate.py` 负责 MySQL、Redis、Realtime Profile、Window Metrics、User Tags、Campaign、Frequency Control、Delivery、Attribution 和 Compensation 的最终状态校验。

Functional runner 会在每个场景开始前清理所有 `ownership.json` 声明的测试数据，并在场景结束及整个 run 结束时再次清理。清理只作用于 `pf-*` 事件、保留的 Functional 用户范围、`PF_TEST_*` Campaign 及其派生记录，同时按真实业务 Key 清理 Redis 的 processed/profile/frequency/delay 状态；它不会执行 `FLUSHDB`、删库或全库截断。reset 会先等待已运行的测试 Kafka consumer group 排空旧 backlog，再进行清理；随后仍会执行有界的清理—稳定性复核收敛，避免活跃消费者在清理期间重新写入而误报。默认会执行 post-clean；需要保留本轮运行态数据调试时显式使用 `-KeepTestData`，下次运行仍会先执行 pre-clean。`testing/functional/state.py --mode verify` 可独立核对当前 test-owned 数据是否为零。

Replay 默认串行以保留到达顺序；`concurrency` 和 `hot-user` 通过 `-Concurrency 8`（或更大值）验证幂等、原子指标更新和实时画像。并发参数是功能正确性工具，不是吞吐量门槛。

没有运行中的应用时可以使用 dry run：

```powershell
.\testing\functional\run.ps1 `
  -Scenario normal `
  -Scale small `
  -BaseUrl http://localhost:18080 `
  -DryRun
```

Dry run 的结果是 `NOT_RUN`，只代表已检查数据生成、场景编排和报告结构，不代表业务链路通过。Campaign 场景继续使用 [`campaign-fixture.sql`](functional/campaign-fixture.sql)；没有公开 HTTP 触发方式的 XXL-JOB 产物会在报告中显示 `NOT_RUN`，不会伪装成 PASS。

需要在失败后保留 MySQL/Redis 运行态数据时：

```powershell
.\testing\functional\run.ps1 -Scenario campaign -KeepTestData
```

`-KeepTestData` 只跳过本轮 post-clean 和 final-clean；pre-clean 仍然执行，避免上一轮中断污染本轮。

## Performance k6

三个场景由同一个 `performance.js` 实现，通过 `SCENARIO=smoke|load|stress` 选择配置；`event.js` 保留事件 payload 生成逻辑。接口保持不变：

```powershell
.\testing\performance\run.ps1 `
  -Scenario smoke `
  -BaseUrl http://localhost:18080

.\testing\performance\run.ps1 `
  -Scenario load `
  -BaseUrl http://localhost:18080

.\testing\performance\run.ps1 `
  -Scenario stress `
  -BaseUrl http://localhost:18080 `
  -AllowStress
```

Smoke 使用少量 VU 和短时长，阈值为错误率 `<5%`、P95 `<1000 ms`、P99 `<2000 ms`；Load 保留 `10 → 50 → 100 → 200 → 0`；Stress 保留 `50 → 100 → 250 → 500 → 0`，阈值为错误率 `<20%`、P95 `<3000 ms`、P99 `<5000 ms`。Stress 没有 `-AllowStress` 时会拒绝执行并返回 `NOT_RUN`，防止误压目标环境。

k6 只判断 HTTP/API 接入、错误率、吞吐和延迟，不调用 Functional Validator，也不判断 MySQL、Redis 或 Campaign 最终业务状态。报告包含 `performance-report.md`、`performance-report.json` 和 `k6-summary.json`。

## AI Dataset 与 API Evaluation

AI 数据集静态完整性检查由 CI 自动执行；本地也可以从仓库根目录运行：

```powershell
python testing/functional/validate_ai_dataset.py
```

该检查验证 JSONL 格式、manifest 与 SHA-256、case 数量、case ID 唯一、required categories、required fields 以及 overlong case 合法性，不启动应用或调用 Provider。

`evaluate_ai.py` 只负责对真实运行中的 PulseFlow AI Campaign API 做专项评估：

```powershell
$env:PULSEFLOW_TOKEN = '<test-token>'
python testing/functional/evaluate_ai.py `
  --base-url http://localhost:18080 `
  --pii-enabled
```

它保留 loopback 安全限制、token/auth、PII enabled、HTTP status expectation、DSL/parser response shape、failure report、duration、case ID 和 category。API Evaluation 不提供离线模式；没有 token 或应用不可达时不会把数据集检查伪装成通过。

## 报告与状态

报告写入 `testing/reports/<run-id>/`，由 `testing/.gitignore` 忽略。单独运行时优先阅读 `functional/functional-report.md` 或 `performance/performance-report.md`；需要 Debug 时再查看场景目录中的 `summary.json`、`failures.json`、`replay-results.jsonl` 和 `k6-summary.json`。

- `PASS`：适用断言全部通过；
- `FAIL`：已执行但至少一项断言失败，或请求/依赖发生错误；
- `NOT_RUN`：依赖、调度任务、显式安全门或当前能力不满足，不能当作 PASS。

需要同时运行两条手工主线时：

```powershell
.\testing\run-all.ps1 `
  -Scale small `
  -BaseUrl http://localhost:18080 `
  -Performance smoke
```

`run-all.ps1` 只负责创建 RunId、编排 Functional/Performance、读取两边最终状态并生成简洁的 `run-all-report.json` 与 `run-all-report.md`。详细场景、失败原因和业务校验仍由各自报告负责。
