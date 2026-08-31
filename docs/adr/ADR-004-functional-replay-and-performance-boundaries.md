# ADR-004：Functional Replay 与 k6 性能测试分层

## 状态

已接受

## 背景

PulseFlow 的事件入口同时需要验证业务最终结果和并发性能。两类测试都调用
`POST /api/events`，但它们的输入、执行顺序、断言和报告口径不同。把 k6 插入 Replay
和 MySQL/Redis 校验之间，会让性能流量干扰异步业务验收，也可能造成报告状态不一致。

## 决策

- Functional Replay 是完整业务验收主线：使用固定 JSONL/Manifest，覆盖正常、幂等、
  乱序、迟到、非法、热点用户、受控并发和 Campaign 场景；等待异步链路后由 Validator
  比较 Expected 与 Actual。
- Replay 默认串行以保留到达顺序；只在明确的并发正确性场景启用有界
  `--concurrency`，并验证最终业务数据，不设置吞吐或延迟阈值。
- k6 是独立性能验收：动态生成合法 `EventRequest`，只判断请求错误率、吞吐、P95/P99
  和稳定性，可以保留最小接入契约断言，但不调用 Functional Validator。
- 两条主线共享接口契约和事件类型，但使用独立的运行入口、命名空间和报告。
- `NOT_RUN` 必须保留为独立状态；没有公开触发方式的 XXL-JOB 产物不能被报告为 PASS。

## 结果

正面：功能结果可以稳定复现，受控并发可以发现幂等和原子更新问题，性能结果不会污染
业务 Oracle，用户只需理解 Functional 和 Performance 两个入口。

代价：完整功能验收比单次 HTTP Replay 更慢；daily/window/tag/compensation 等只能由
外部调度触发的阶段需要在报告中显示 `NOT_RUN`，直到项目提供安全的测试触发方式。

## 不采用的方案

- 让 k6 随机事件直接进入业务 Validator：没有确定性的 Expected，不能证明业务结果；
- 让 Replay 变成压测框架：会牺牲固定顺序、失败证据和可重现性；
- 为每个场景继续增加 PowerShell 入口：场景由 `functional/run.ps1` 编排即可。
