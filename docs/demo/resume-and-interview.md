# PulseFlow 项目描述与技术讨论要点

配套：[README](../../README.md) · [演示脚本](demo-scenario.md) · [架构文档](../../pulseflow-design.md)。以下描述反映当前确定性 Java 业务系统；Pydantic AI Agent Service 尚未接入。

## 项目描述

- 使用 Kafka、MySQL、Redis 和 XXL-JOB 构建事件驱动的用户画像与 Campaign 决策链路，覆盖行为接入、实时/窗口/标签画像、规则选择、Lua 原子频控、幂等触达和 Last-Touch 归因。
- 将 Campaign DSL、字段注册、规则转换、人群预估、草稿生命周期、优惠事实校验和绩效汇总归入 Campaign 业务模块。草稿由登录态操作员拥有，确认时重新校验 DSL，正式 Campaign 仍走确定性执行链路。
- 使用 GitHub Actions、JUnit 和 Testcontainers 验证业务回归及 Flyway V1–V5 迁移。历史迁移保持不可变；旧 Java LLM Runtime 已移除，未来 Agent 通过有权限边界的业务 API 接入。

## 可展开的技术讨论

### 事件可靠性

事件由 Kafka at-least-once 投递。`eventId` 唯一索引保证 MySQL 事实落盘幂等；重复事件从数据库读取规范记录继续后续处理。画像或决策阶段的基础设施失败进入补偿任务，由 Job 重试。Redis 保存可重建的实时状态，MySQL 保存权威事实。

### Campaign 与触达

Campaign 的 EVENT、DELAYED 和 SCHEDULED 触发模式最终由同一规则引擎处理。频控在 Redis Lua 脚本中原子完成额度检查、扣减与重试标记；`delivery_task.dedup_key` 防止重复触达。点击与转化按宽限窗口完成 Last-Touch 归因。

### DSL 与人工确认

`CampaignDslValidator` 根据 `CampaignFieldRegistry` 检查字段、类型、操作符、数值范围、时间与频控。`SqlAudiencePreviewService` 返回预估人数和数据版本。`CampaignDraftService` 处理编辑、过期、归属、刷新预估与幂等确认。未来 Agent 提交的类型化 Proposal 仍需经过这些 Java 规则；本阶段没有自然语言解析服务。

### 绩效与指标口径

`PerformanceSummaryCalculator` 从 Delivery、Click 和 Attribution 事实计算人数与比率，写入 `campaign_performance_summary`。基线、精度、分母为零等口径见 [指标字典](../campaign-metrics-glossary.md)。`CampaignPerformanceSummaryJob` 不调用模型。

### Docker 测试兼容

本地 Docker Desktop 29.x 把最低 Docker API 提升到 1.44，旧 Testcontainers 1.19.7 无法发现 Docker；升级并通过 BOM 对齐到 1.21.4 后，本地和 CI 可运行。Docker IT 本地由 `PULSEFLOW_TEST_DOCKER=true` 开启；CI 的 enforcer profile 强制设置此变量，避免迁移与幂等测试被静默跳过。
