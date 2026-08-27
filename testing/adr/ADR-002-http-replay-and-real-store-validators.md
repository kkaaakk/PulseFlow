# ADR-002：通过真实接入链路 Replay 并校验真实存储

> 中文说明：本决策确定固定数据必须从真实 `POST /api/events` 入口按文件顺序发送，
> 再直接校验真实 MySQL 表和 Redis Key。Validator 只比较事实与 Manifest，不复制
> Campaign 或 Attribution 的业务算法。

## 状态

已接受

## 背景

仅靠随机 Load 无法稳定复现重复或乱序问题。PulseFlow 的正确性依赖
`HTTP → Kafka → EventConsumer → MySQL / Redis → Campaign` 这条顺序链路，因此测试
必须保留数据集到达顺序，并将持久化事实与预先计算的预期结果进行比较。

## 决策

通过 `POST /api/events` 按 JSONL 文件顺序 Replay，记录每个响应，再查询真实的
`user_event`、指标、Campaign、Attribution 和 Redis Key。Validator 不重新实现
Campaign 或 Attribution 算法，只比较源码可观测事实与 Manifest；如果当前 API
无法产生某项事实，则明确暴露这个限制。

## 影响

### 正面影响

- 未来修复 Bug 后，原始失败输入仍然可以重新 Replay；
- MySQL/Redis 不一致会直接暴露，不会被 HTTP 200 隐藏；
- Manifest 成为稳定的预期结果边界。

### 负面影响

- 完整验收需要运行中的应用、MySQL、Kafka 和 Redis；
- 异步消费必须增加明确的等待/轮询阶段；
- 需要 `click_event` 的 Attribution 场景必须准备测试专用 SQL，因为当前公开 API
  没有点击记录接口。

## 备选方案

- **只使用 JUnit Mock**：无法覆盖端到端持久化和顺序问题，因此不采用；
- **只使用 k6 随机流量**：无法保留固定的重复/冲突 Payload，也无法得到确定性的
  聚合预期，因此不采用。
