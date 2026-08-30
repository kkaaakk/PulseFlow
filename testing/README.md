# PulseFlow Testing

`testing/` 只提供两条主线：

- Functional：固定数据集 Replay 完整业务链路，并由 Validator 比较 Expected/Actual；支持确定性大数据和受控并发。
- Performance：k6 产生并发流量，只判断请求错误率、吞吐和延迟，不判断 MySQL、Redis 或 Campaign 最终业务结果。

## 目录

```text
testing/
├── README.md
├── ADR-004-functional-replay-and-performance-boundaries.md
├── common.ps1
├── docker-compose.test.yml
├── functional/
│   ├── generate.py
│   ├── replay.py
│   ├── validate.py
│   ├── verify_contract.py
│   ├── evaluate_ai.py
│   ├── validate_ai_dataset.py
│   ├── campaign-fixture.sql
│   └── run.ps1
├── performance/
│   ├── event.js
│   ├── smoke.js
│   ├── load.js
│   ├── stress.js
│   └── run.ps1
├── data/
│   ├── ai/
│   ├── fixtures/
│   └── generated/          # 运行时生成，已忽略
└── reports/                # 运行时报告，已忽略
```

Maven/JUnit 测试继续留在 `pulseflow/**/src/test`，不复制到这里。

## 前置条件

Functional 需要运行中的 PulseFlow、Kafka、MySQL 和 Redis。默认只允许 loopback URL、
测试环境和测试数据库。可以先启动隔离依赖：

```powershell
docker compose -f .\testing\docker-compose.test.yml -p pulseflow-test up -d
```

应用需要连接 `pulseflow_test`、MySQL `13306`、Redis `16379` 和 Kafka `19092`；完整
环境变量示例见 [`.env.test.example`](.env.test.example)。

## Functional Replay

```powershell
.\testing\functional\run.ps1 -Scale small -BaseUrl http://localhost:18080 -RebaseEventTime
```

默认顺序覆盖：fixture、normal、duplicate、out-of-order、late、invalid、hot-user、concurrency
和 campaign。只跑一个范围时使用 `-Scenario normal`、`-Scenario edge`、
`-Scenario concurrency` 或 `-Scenario campaign`。

Replay 默认串行以保留到达顺序；`concurrency` 和 `hot-user` 使用受控 Worker，并通过
`-Concurrency 8`（或更大值）验证并发下的幂等、原子指标更新和实时画像。并发不是性能
门槛，最终仍以业务结果校验为准。

```powershell
.\testing\functional\run.ps1 -Scenario concurrency -Scale small `
  -BaseUrl http://localhost:18080 -Concurrency 8 -RebaseEventTime
```

没有运行中的应用时可以用 `-DryRun` 只检查数据生成、场景编排和报告结构；它的结果是
`NOT_RUN`，不代表业务链路通过。

报告位于 `testing/reports/<run-id>/`。同时运行两条主线时，优先阅读
`run-all-report.md`；功能详情看 `functional/functional-report.md`，性能详情看
`performance/performance-report.md`。JSON 文件和每个场景目录仍保留 Replay 结果、失败证据以及
MySQL/Redis/Profile/Campaign/Attribution/Compensation 的机器可读校验数据。

当前没有公开 HTTP 入口触发 XXL-JOB 的 daily/window/tag/campaign-selection/compensation 任务；这些阶段会
在报告中明确显示 `NOT_RUN`，不会伪装为 PASS。Campaign Attribution 的 raw `CLICK`
仍需要测试 Fixture，因为当前 raw-event consumer 不会自动写入 `click_event`。

如果已通过 XXL-JOB Admin 手动触发 daily/window/tag 等任务，再追加 `-JobsTriggered`；
此时缺少理论上应生成的结果会判为 `FAIL`，而不是 `NOT_RUN`。

Campaign Attribution 默认等待 600 秒：Fixture 的目标事件相对基准时间为 +3 分钟，
再加上 5 分钟 attribution grace window。

可选地在 Functional 主线前执行 Maven：

```powershell
.\testing\functional\run.ps1 -RunMaven -PrepareDependencies -Scale small -BaseUrl http://localhost:18080
```

## Performance k6

k6 使用项目的 `EventRequest` 字段和真实 `EventType`，但事件在运行时随机生成，不读取
Functional JSONL，也不调用 Functional Validator。

```powershell
.\testing\performance\run.ps1 -Scenario smoke -BaseUrl http://localhost:18080
.\testing\performance\run.ps1 -Scenario load -BaseUrl http://localhost:18080
.\testing\performance\run.ps1 -Scenario stress -BaseUrl http://localhost:18080 -AllowStress
```

Smoke/Load/Stress 报告保存 `performance-report.md`、`k6-summary.json` 和 `performance-report.json`。k6 的最小
接入断言是 HTTP 200、`ApiResponse.code=200` 和 `data.accepted=true`；最终落库正确性
不属于 k6 的通过标准。

## AI 数据集

AI 数据集位于 `data/ai/`，默认只做离线覆盖检查：

```powershell
python testing/functional/validate_ai_dataset.py
python testing/functional/evaluate_ai.py --offline
```

Real Provider/API 评估必须显式提供认证和运行中的测试应用，不会自动混入 Event Replay。

## 状态含义

- `PASS`：适用的断言全部通过；
- `FAIL`：至少一个断言失败；
- `NOT_RUN`：依赖、调度任务或当前源码能力不具备，不能当作通过。

一次同时运行两条主线时使用：

```powershell
.\testing\run-all.ps1 -Scale small -BaseUrl http://localhost:18080 -Performance smoke
```

`run-all.ps1` 会分别生成 Functional 和 Performance 报告，再汇总
`functionalStatus`、`performanceStatus` 和 `overallStatus`。

人工阅读时优先打开 `testing/reports/<run-id>/run-all-report.md`；如果功能验收有问题，
再看 `functional/functional-report.md` 和对应场景的 `summary.md`。需要深入 Debug 时，
再查看 `summary.json`、`failures.json` 和 `k6-summary.json`。
