# PulseFlow Engineering Verification

## 本地与 CI

在 `pulseflow/` 运行 `mvn clean test` 和 `mvn clean verify`。`BusinessRuntimeBootstrapIT` 验证 Campaign 校验组件无需 Java AI Runtime 即可装配；内含 Docker 条件下的 Flyway V1–V5 迁移验证。`EventIdempotentConsumptionIT` 验证 Kafka 消费与数据库幂等。CI 使用 `PULSEFLOW_TEST_DOCKER=true` 强制 Docker 集成测试。

在 `pulseflow-web/` 运行 `npm ci`、`npm run typecheck`、`npm run lint`、`npm run test`、`npm run build`、`npm run test:e2e:demo`。真实后端 E2E 使用 `npm run test:e2e:integration`，需先启动 MySQL、Kafka、Redis 与 PulseFlow Boot。

## 核心回归

- Event：接入、Kafka 幂等消费、MySQL 事实落盘。
- Profile：Redis 实时画像、MySQL 窗口指标与标签、降级查询。
- Campaign：DSL 校验、规则转换、人群预估、草稿生命周期、归属与确认幂等、决策选择。
- Delivery：频控、任务去重、渠道发送。
- Attribution：点击关联、宽限窗口与 Last-Touch 归因。
- Analytics：Campaign Performance Summary 根据 Delivery、Click、Attribution 计算并持久化。

Flyway 历史 migration 保持原 checksum。旧 LLM Provider、Prompt、模型解析器和 AI Review Job 已删除。指标定义见 [Campaign Metrics Glossary](campaign-metrics-glossary.md)。
