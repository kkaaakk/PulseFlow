# Pydantic AI 接入前清理报告

Base SHA: `97fa3982219c4698221ce6216353dde0b60c167b`
Branch: `codex/pydantic-agent-preparation`

## Deleted

- Java LLM Provider：`AiModelClient`、`AiRequest`、`AiResponse`、`OpenAiCompatibleClient`、`FakeAiModelClient`。
- Prompt Runtime：`com.pulseflow.ai.prompt` 与四份 prompt 资源；模型文本 JSON 解析器 `AiOutputParser`。
- 一次调用型 AI Service：`CampaignIntentService`、`AudienceInsightService`、`CampaignContentService`、`CampaignReviewService`；旧 AI Review 状态机、API、重试与审计 Mapper。
- Java AI 配置与观测：`AiFeatureProperties`、`AiAutoConfiguration`、`AiAuditService`、`AiMetrics`、Azure PII SDK 与 Client、旧 Provider/PII 环境变量。
- 前端旧 Copilot 页面、路由、侧边栏入口、AI Review 展示、专属 API/types/demo 数据和 E2E 流程。
- 旧自然语言解析 API 评测、dataset、CI 静态校验，以及仅覆盖 LLM/Prompt/Parser/Review 输出的测试。
- 完整的 `pulseflow-ai` Maven 模块；Boot/Job 对它的依赖。

## Moved

| Old path | New path |
|---|---|
| `pulseflow-ai/.../domain/campaign/*` | `pulseflow-campaign/.../dsl/*` |
| `pulseflow-ai/.../guardrail/AiFieldRegistry.java` 等 DSL Guardrail | `pulseflow-campaign/.../validation/*` |
| `pulseflow-ai/.../application/CampaignAiDraftService.java` 与 draft Entity/Mapper | `pulseflow-campaign/.../draft/*` |
| `pulseflow-ai/.../application/AudiencePreview*.java` 与 `SqlAudiencePreviewService.java` | `pulseflow-campaign/.../preview/*` |
| `pulseflow-ai/.../infrastructure/persistence/AudienceMetricsAggregator.java`、`PerformanceSummaryCalculator.java` 与 Entity/Mapper | `pulseflow-campaign/.../analytics/*` |
| `pulseflow-ai/.../guardrail/ContentFactValidator.java` 与 content DTO | `pulseflow-campaign/.../content/*` |
| `pulseflow-ai` 中 DSL、Draft、Content 业务测试 | `pulseflow-campaign/src/test/...` |
| `docs/ai-metrics-glossary.md` | `docs/campaign-metrics-glossary.md` |

## Renamed

- `AiFieldRegistry` → `CampaignFieldRegistry`。
- `CampaignAiDraftService` → `CampaignDraftService`；`CampaignAiDraft` → `CampaignDraft`（数据库表名不变）。
- `ContentFactValidator` → `CampaignContentValidator`。
- `CampaignReviewJob` → `CampaignPerformanceSummaryJob`，只调用确定性计算器。
- `AiModeBootstrapIT` → `BusinessRuntimeBootstrapIT`；保留 Flyway V1–V5 验证，移除 AI 双模式断言。

## Preserved

- Campaign DSL、字段注册表、校验器和规则转换：业务规则必须由 Java 最终裁决。
- SQL Audience Preview：返回预估人数、数据版本和 warnings，可作为未来 Tool 的业务底层。
- Campaign Draft lifecycle：保存、编辑、刷新预估、归属、过期、确认、幂等确认和创建 Campaign/Rule。
- Audience Metrics 与 Campaign Performance Summary：Java 从权威 Profile、Delivery、Click、Attribution 事实计算，不依赖 LLM。
- Campaign Content Validator：保留优惠事实、长度、禁词、PII 模式等确定性约束。
- 事件接入、Kafka、画像、Campaign Selection、频控、Delivery、Attribution 核心语义。

## Database

- `campaign_ai_draft` 仍被 `CampaignDraft` 使用，`@TableName` 保持兼容。
- `campaign_performance_summary` 仍由绩效计算器和 Job 使用。
- `ai_generation_record`、`campaign_ai_review`、`campaign_content_variant` 为历史表，Java runtime 不再读写。
- 未修改或删除 Flyway V1–V5，尤其没有改写 `V3__ai_campaign_tables.sql` 或追加 DROP TABLE migration。V3 Git blob SHA 与 base 均为 `7294e41a3a0d245e618c153f80fac37d11327563`。

## Future Agent Integration Points

下一阶段独立 Python + Pydantic AI Agent Service 可以经鉴权 Tool API 调用 Campaign Metrics、Audience Metrics、Audience Preview、Campaign Performance、Attribution、Campaign Draft 和 Campaign Confirm。Java 仍负责验证、归属和人工确认。本阶段没有实现 Agent Tool、LLM、Agent Loop 或 Pydantic AI。出站 PII 行为要求见 [PII Guardrail Contract](pii-guardrail-contract.md)。

## Verification

- `mvn clean test`：通过；包括迁移后的 DSL/Draft/Content 测试与新 Audience Preview、Performance Summary 测试。
- `mvn clean verify`（未设置 `PULSEFLOW_TEST_DOCKER`）：通过；Docker 条件测试跳过。
- `PULSEFLOW_TEST_DOCKER=true; mvn clean verify`：本机 Docker Desktop daemon 不可用，Testcontainers 在启动容器前报 `Could not find a valid Docker environment`。GitHub CI Run [36107086298](https://github.com/kkaaakk/PulseFlow/actions/runs/36107086298) 已在 Docker Runner 上完成相同的 `mvn clean verify`，包括 Flyway V1–V5 与事件幂等集成测试。
- 前端 `typecheck`、`lint`、`test`、`build`、Demo E2E：通过。
- `mvn dependency:tree -Dincludes=com.pulseflow:pulseflow-ai`：8 模块 reactor 成功，无匹配依赖。
- 旧 LLM 符号与配置在生产代码、前端源码、测试脚本中全局搜索为 0。
- V3 migration 内容与 base Git blob 完全一致。
- `testing/functional` Python 单测：9/9 通过。
- PR #10 的 `build-and-test`：通过，后端与前端 CI 均绿色。
