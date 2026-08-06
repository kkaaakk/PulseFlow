# AI Campaign Copilot — 指标口径字典（Metrics Glossary）

> 阶段 7.1 产出。本文档定义 `AudienceMetricsAggregator` 与 `PerformanceSummaryCalculator`
> 产出的每个指标的数据来源、时间窗口、计算方式、空值含义与精度规则。
>
> 目的：让 AI 洞察/复盘的输入可审计，并约束 LLM 输出与 evidence 校验口径一致
> （`InsightEvidenceValidator` / `ReviewEvidenceValidator` 依据本口径做数字一致性校验）。

## 1. 通用约定

| 约定 | 规则 |
|---|---|
| 比例（rate）内部表示 | **0 ~ 1** 的 `BigDecimal`，4 位小数，`HALF_UP`。证据校验时 0.126 等价于 12.6%。 |
| 金额/均值 | `BigDecimal`，2 位小数，`HALF_UP`，单位元。 |
| 人数 | `long`，整数。 |
| 分母为 0 | **返回 `0`（`BigDecimal.ZERO`）**，不返回 null。校验器需容忍 0。 |
| 浮点比较容差 | evidence 校验允许 ±0.01（比例）/ ±0.01（金额）的取整误差。 |
| “提高 X%” vs “提高 X 个百分点” | 二者**不可混淆**。比例差（相对提升）= (new-old)/old；百分点差（绝对）= new-old。校验器按 LLM 表述匹配对应口径。 |
| 数据版本 | 每次聚合在 LLM 输入中附带 `calculatedAt` / `profileDataVersion` / `baselineDataVersion`，便于审计追溯。 |

## 2. 候选池与基线

| 项 | 口径 |
|---|---|
| 候选池（candidate pool） | `user_profile` 中 `status=1` 的用户，`LIMIT 50000`。 |
| 匹配人群（matched） | 候选池 ∩ DSL 人群条件（v1 按 AND 聚合，OR 未完全支持，回退 AND）。 |
| 基线（baseline） | **v1 简化：用候选池本身作为 site-wide 基线**，而非全站。已知局限：候选池有 50000 上限且仅含 `status=1` 用户，与真实全站存在偏差。 |
| matched 为空 | 返回 `audienceCount=0` 且其余指标字段为 null 的 `AudienceMetrics`。 |

> ⚠️ 已知口径风险（阶段 7.1 记录，后续可优化）：
> 1. baseline 与目标人群使用同一候选池，未区分时间窗口版本；
> 2. Redis 实时指标与 MySQL 日快照未在 v1 混用（聚合仅读 `user_behavior_summary` 快照）；
> 3. 标签更新时间不同却直接比较——`evalOne` / `computeTagRatios` 已取每个用户 `calculatedAt` 最新一条，缓解但不消除时间错配。

## 3. 人群洞察指标（AudienceMetrics）

实现：`com.pulseflow.ai.infrastructure.persistence.AudienceMetricsAggregator`
数据源：`user_profile` / `user_tag` / `user_behavior_summary`

| 指标 | 数据来源 | 时间窗口 | 计算方式 | 空值/零值含义 |
|---|---|---|---|---|
| `audienceCount` | matched 集合 | 当前快照 | `matched.size()` | 0 表示无匹配人群 |
| `activeRate7d` | `user_behavior_summary(metric_type='active_7d')` | 7 天 | `active_7d>0 人数 / matched 人数`（取每用户最新一条） | matched 为空返回 0 |
| `averageSpend30d` | `user_behavior_summary(metric_type='spend_30d')` | 30 天 | `sum(每用户最新 spend_30d) / matched 人数`，2 位小数 | 无数据返回 0 |
| `averageOrderCount30d` | `user_behavior_summary(metric_type='order_count_30d')` | 30 天 | `sum(每用户最新 order_count_30d) / matched 人数`，2 位小数 | 无数据返回 0 |
| `cartWithoutPurchaseRate` | `active_7d` + `spend_30d` | 7d/30d 代理 | **v1 代理口径**：`active_7d>0 且 spend_30d==0 人数 / matched 人数` | matched 为空返回 0 |
| `highValueRate` | `user_tag(tag_name='HIGH_VALUE', tag_value='1')` | 当前标签版本 | `HIGH_VALUE 用户数 / matched 人数` | 无标签返回 0 |
| `priceSensitiveRate` | `user_tag(tag_name='PRICE_SENSITIVE', tag_value='1')` | 当前标签版本 | `PRICE_SENSITIVE 用户数 / matched 人数` | 无标签返回 0 |
| `churnRiskRate` | `user_tag(tag_name='CHURN_RISK', tag_value='1')` | 当前标签版本 | `CHURN_RISK 用户数 / matched 人数` | 无标签返回 0 |
| `baseline.activeRate7d` | 候选池 | 7 天 | 同 `activeRate7d` 但分母为候选池人数 | — |
| `baseline.averageSpend30d` | 候选池 | 30 天 | 同 `averageSpend30d` 但用候选池 | — |
| `baseline.cartWithoutPurchaseRate` | 候选池 | 7d/30d 代理 | 同代理口径，分母为候选池 | — |

> v1 不提供的字段：`topCategories`、`memberLevelDistribution`（schema 暂无直接来源，置 null）。

### 3.1 `cartWithoutPurchaseRate` 口径警告

当前实现是**代理指标**（活跃但未消费），并非真正的“加购未购”。LLM prompt 与复盘结论中**不得**将其表述为“加购未支付率”。后续接入 `cart_count` 事件后可切换为真实口径。

## 4. 活动复盘指标（PerformanceSummary）

实现：`com.pulseflow.ai.infrastructure.persistence.PerformanceSummaryCalculator`

| 指标 | 数据来源 | 计算方式 | 空值含义 |
|---|---|---|---|
| `impressions` / `clicks` / `conversions` | `delivery_record` 聚合 | 活动周期内 sum | 无记录为 0 |
| `ctr` | clicks / impressions | 比例，4 位小数 | impressions=0 返回 0 |
| `conversionRate` | conversions / impressions | 比例，4 位小数 | impressions=0 返回 0 |
| `totalSpend` / `totalRevenue` | 订单/投放聚合 | sum，2 位小数 | 无记录为 0 |
| `roas` | totalRevenue / totalSpend | 2 位小数 | totalSpend=0 返回 0 |
| `audienceSize` | 活动命中人群快照 | 计数 | — |

### 4.1 复盘证据校验要点

- LLM 引用的所有数字必须能在 `PerformanceSummary` / `AudienceMetrics` 中找到对应 `evidenceKey`。
- “转化率提高 7.9%”会被校验器区分：
  - 若 LLM 表述为“相对提升 7.9%” → 校验 `(new-old)/old ≈ 0.079`；
  - 若 LLM 表述为“提高 0.3 个百分点” → 校验 `new-old ≈ 0.003`。
- 数据不足（如 `audienceSize=0` 或 `impressions=0`）时，`CampaignReviewService` 不生成结论性陈述，仅记录事实，避免 AI 强行总结。

## 5. 与 evidence 校验的对接

| 校验器 | 校验内容 | 失败行为 |
|---|---|---|
| `InsightEvidenceValidator` | insight 中引用的指标值与 `AudienceMetrics` 一致；未知 `evidenceKey` 丢弃 | 数字不一致 → 422 |
| `ReviewEvidenceValidator` | review 中引用的指标值与 `PerformanceSummary` 一致；区分相对提升/百分点 | 数字不一致 → 422 |
| `ContentFactValidator` | 文案中的优惠事实以服务端草稿为准，前端/LLM 不得覆盖 | 事实冲突 → 422 |

## 6. 变更流程

新增/修改指标口径时，必须同步：
1. 更新本字典；
2. 更新对应 `AiFieldRegistry` 字段注册；
3. 增补 `InsightEvidenceValidator` / `ReviewEvidenceValidator` 的边界用例；
4. 在 `CampaignReviewFlowTest` / guardrail 单测中覆盖新口径。
