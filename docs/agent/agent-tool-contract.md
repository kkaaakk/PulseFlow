# Agent 内部只读 Tool API（Phase 2）

Java 在 `/internal/v1/agent-tools` 提供六个只读业务端点。Phase 6 另加入严格授权的草稿创建端点，权限规则见 [Proposal 与人工确认](campaign-proposal-approval.md)。请求不能包含 SQL、表名、列名或单用户标识。

## 部署与认证

- 请求头：`X-PulseFlow-Agent-Token`，与 Java 的 `PULSEFLOW_AGENT_INTERNAL_TOKEN` 使用同一机密。未配置时所有内部 Tool 请求返回 503；缺失或错误返回 401。比较 SHA-256 摘要使用 `MessageDigest.isEqual`。
- 此机密独立于 Sa-Token 的浏览器登录凭证。内部端点不接受前端传入的 `operatorId`，也不通过浏览器会话推断操作者。
- **部署必须在入口代理阻断公网对 `/internal/**` 的访问，并只允许 Agent Service 所在内网访问 Java 服务。** 目前仓库没有生产 Compose / ingress 配置；上线前须在实际部署配置中落实该网络规则。不要把内部 Token 注入浏览器。
- 日志、异常响应和 trace 不包含 Token、原始请求体或用户明细。Tool 异常只返回固定错误码。

## 通用契约

时间范围使用带偏移量的 ISO-8601 `fromInclusive` / `toExclusive`，起点包含、终点不包含，最长 31 天；数据库时间桶按 `Asia/Shanghai`。`rowLimit` 默认 50，允许 1–100。请求的 `dimensions` 目前最多一个。超出行数时只返回稳定排序后的前 N 行，并给出 `row_limit_reached`。维度值为 `null` 表示事实中该维度缺失；无维度查询的维度 Map 为空。

过滤器仅支持 `CAMPAIGN_ID`、`CHANNEL`；操作符仅支持 `EQ`（一个值）、`IN`（最多 10 个值）。`CHANNEL` 值必须为现有 `ChannelType`，所有值都走参数绑定。可分组维度仅有 `CAMPAIGN`、`CHANNEL`、`DAY`；归因另支持 `MODEL`。不提供 `IMPRESSION`、`PLATFORM`、`USER_SEGMENT` 等缺乏权威事实的字段。

每个成功响应包含 `metadata.queryId`、`generatedAt`、`dataVersion`、`source`、`warnings`。实时事实聚合没有固定快照版本，`dataVersion=null`；Campaign 绩效使用汇总计算时间，人群预估使用画像版本。`queryId` 用于后续 Evidence 关联，不是持久化查询记录。

## 端点

| Method / Path | 输入 | 输出与语义 |
|---|---|---|
| `POST /metrics/query` | `metric,timeRange,filters,dimensions,rowLimit` | 聚合 `rows[{dimensions,value,sampleSize}]` 与总 `sampleSize` |
| `POST /metrics/compare` | `metric,currentPeriod,baselinePeriod,filters,dimensions,rowLimit` | Java 计算 `current,baseline,absoluteDelta,relativeDelta`；基线为 0 时相对变化为 `null` 并警告。`DAY` 不支持跨期直接比较。 |
| `POST /metrics/breakdown` | `metric,timeRange,filters,dimension,rowLimit` | 指定一个白名单维度的聚合查询。 |
| `GET /campaigns/{campaignId}/performance` | 正整数 Campaign ID | 读取 `PerformanceSummaryCalculator` 已生成的 `CampaignPerformanceSummary`；缺失时 `available=false`，不会触发计算器的写入路径。 |
| `POST /attribution/breakdown` | `timeRange,filters,dimension,rowLimit` | `attributionCount` 与 `uniqueConverters`，只返回聚合结果。 |
| `POST /audience/preview` | `dsl: CampaignDsl` | 先调用 `CampaignDslValidator`，再调用 `AudiencePreviewService`；无效 DSL 不执行预估。返回 `estimatedCount,dataVersion,validation`，验证错误和预估警告采用安全代码，不回显原值。预估失败时 `estimatedCount=null`，避免把故障误报成零人。 |
| `POST /campaign-drafts` | `investigationId,proposal` 和独立 `X-PulseFlow-Draft-Grant` | PROPOSE 权限，复用 Java 校验/预估/草稿服务；仅写草稿，没有 confirm/activate 内部端点。 |

指标白名单：`SENT`、`DELIVERED`、`CLICKS`、`CONVERSIONS`、`CTR`、`CONVERSION_RATE`、`ATTRIBUTED_CONVERSIONS`。`SENT` 对应发送记录数，包括失败记录；`DELIVERED` 对应 `status IN ('SENT','DELIVERED')`。`CLICKS` 和 `CONVERSIONS` 是按用户去重的人数；`ATTRIBUTED_CONVERSIONS` 是归因记录数。`CTR=CLICKS/DELIVERED`，`CONVERSION_RATE=CONVERSIONS/CLICKS`，复用 `PerformanceSummaryCalculator.rate` 的四位小数、`HALF_UP`、零分母返回 0 语义；零分母同时警告。`sampleSize` 是该指标的计数来源或比率分母。时间窗按每类事实自己的事件时间过滤，所以跨事实比率是**同窗事件比率**，不表示同一批触达用户的因果转化率。

任何响应都不包含 `userId`、原始 click/order/event 行、SQL、表结构或数据库凭证。Java 业务系统仍是聚合口径和合法字段的唯一权威。
