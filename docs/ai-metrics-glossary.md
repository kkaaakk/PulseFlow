# AI Campaign Copilot — 指标口径字典

> 定义 `AudienceMetricsAggregator` 与 `PerformanceSummaryCalculator` 产出的每个指标的数据来源、计算方式、空值含义与精度规则。
> 
> 目的：让 AI 洞察/复盘的输入可审计，约束 LLM 输出与 evidence 校验口径一致（`InsightEvidenceValidator` / `ReviewEvidenceValidator` 依据本口径做数字一致性校验）。

## 1. 通用约定

| 约定 | 规则 |
|---|---|
| 比例（rate）内部表示 | **0 ~ 1** 的 `BigDecimal`，4 位小数，`HALF_UP`。证据校验时 0.126 等价于 12.6%。 |
| 金额/均值 | `BigDecimal`，2 位小数，`HALF_UP`，单位元。 |
| 人数 | `long`，整数。 |
| 分母为 0 | **返回 `0`（`BigDecimal.ZERO`）**，不返回 null。校验器需容忍 0。 |
| 浮点比较容差 | evidence 校验允许 ±0.01（比例）/ ±0.01（金额）的取整误差。 |
| "提高 X%" vs "提高 X 个百分点" | 二者不可混淆。比例差（相对提升）= (new-old)/old；百分点差（绝对）= new-old。校验器按 LLM 表述匹配对应口径。 |
| 数据版本 | 每次聚合在 LLM 输入中附带 `calculatedAt` / `profileDataVersion` / `baselineDataVersion`，便于审计追溯。 |

## 2. 候选池与基线

| 项 | 口径 |
|---|---|
| 候选池（candidate pool） | `user_profile` 中 `status=1` 的用户，`LIMIT 50000`。 |
| 匹配人群（matched） | 候选池 ∩ DSL 人群条件。AND 逻辑取交集（`retainAll`），OR 逻辑取并集（`addAll`）。 |
| 基线（baseline） | **v1 用候选池作为 site-wide 基线**，而非全站。已知局限：候选池有 50000 上限且仅含 `status=1` 用户，与真实全站存在偏差。 |
| matched 为空 | 返回 `audienceCount=0` 且其余指标字段为 null 的 `AudienceMetrics`。 |

> 已知口径风险（v1）：
> 1. baseline 与目标人群使用同一候选池，未区分时间窗口版本；
> 2. 聚合仅读 `user_behavior_summary` 快照，不含 Redis 实时指标；
> 3. 标签取每用户最新 `calculatedAt` 一条，缓解但不消除时间错配。

## 3. 人群洞察指标

实现：`AudienceMetricsAggregator`  
数据源：`user_profile` / `user_tag` / `user_behavior_summary`

| 指标 | 数据来源 | 计算方式 | 空值/零值含义 |
|---|---|---|---|
| `audienceCount` | matched 集合 | `matched.size()` | 0 = 无匹配人群 |
| `activeRate7d` | `user_behavior_summary(metric_type='active_7d')` | `active_7d>0 人数 / matched 人数`（取每用户最新值） | matched 为空返回 0 |
| `averageSpend30d` | `user_behavior_summary(metric_type='spend_30d')` | `sum(每用户最新 spend_30d) / 有 spend_30d 数据的用户数`，2 位小数。**分母不含无 spend_30d 记录的用户** | 无数据用户不参与除法；全无数据返回 0 |
| `averageOrderCount30d` | `user_behavior_summary(metric_type='order_count_30d')` | 同 `averageSpend30d`，分母为有该指标数据的用户数 | 同上 |
| `cartWithoutPurchaseRate` | `active_7d` + `spend_30d` | **代理口径**：`active_7d>0 且 spend_30d==0 人数 / matched 人数`。非真正的"加购未购"，不得在 LLM 输出中表述为加购相关术语 | matched 为空返回 0 |
| `highValueRate` | `user_tag(tag_name='HIGH_VALUE')` | `HIGH_VALUE 用户数 / matched 人数`（取每用户最新标签） | 无标签返回 0 |
| `priceSensitiveRate` | `user_tag(tag_name='PRICE_SENSITIVE')` | 同上 | 无标签返回 0 |
| `churnRiskRate` | `user_tag(tag_name='CHURN_RISK')` | 同上 | 无标签返回 0 |
| `baseline.*` | 候选池 | 同上口径但分母为候选池人数 | — |

v1 不提供的字段：`topCategories`、`memberLevelDistribution`（schema 暂无直接来源，置 null）。

## 4. 活动复盘指标

实现：`PerformanceSummaryCalculator`  
数据源：`delivery_task` / `delivery_record` / `click_event` / `attribution_record` / `campaign_performance_summary`

### 4.1 基础计数

| 字段 | 口径 |
|---|---|
| `targetAudienceCount` | `delivery_task` 中去重 `user_id` 的个数 |
| `sentCount` | `delivery_record` 记录数 |
| `deliveredCount` | `delivery_record` 中 `status IN ('SENT','DELIVERED')` 的记录数 |
| `clickedCount` | `click_event` 中通过 `task_id` 关联的去重 `user_id` 数 |
| `convertedCount` | `attribution_record` 中去重 `user_id` 数 |
| `unsubscribeCount` | v1 固定为 0（schema 未追踪退订） |

### 4.2 派生比率

| 比率 | 公式 | 分母为 0 |
|---|---|---|
| `deliveryRate` | `deliveredCount / sentCount` | 返回 0 |
| `clickRate` | `clickedCount / deliveredCount` | 返回 0 |
| `conversionRate` | `convertedCount / clickedCount` | 返回 0 |
| `unsubscribeRate` | `unsubscribeCount / sentCount` | 返回 0（v1 固定） |

### 4.3 历史基线

`baseline.clickRate` / `baseline.conversionRate`：取所有已完成（已有 `campaign_performance_summary`）的其他 Campaign 的 `clickRate` / `conversionRate` 均值作为粗粒度对照。

### 4.4 variantMetrics

v1 schema 不直接支持 A/B 分变体，固定为空数组 `[]`。prompt 中保留该字段以保持 schema 稳定。

## 5. 复盘证据校验要点

- LLM 引用的所有数字必须能在 `CampaignPerformanceSummary` 中找到对应 `evidenceKey`。
- "转化率提高 7.9%"会被校验器区分：
  - 若 LLM 表述为"相对提升 7.9%" → 校验 `(new-old)/old ≈ 0.079`；
  - 若 LLM 表述为"提高 0.3 个百分点" → 校验 `new-old ≈ 0.003`。
- 数据不足（如 `audienceCount=0` 或 `sentCount=0`）时，`CampaignReviewService` 不调用 AI、不生成结论，标记 `SKIPPED_INSUFFICIENT_DATA` 或 `DATA_NOT_READY`。

## 6. 与 evidence 校验的对接

| 校验器 | 校验内容 | 失败行为 |
|---|---|---|
| `InsightEvidenceValidator` | insight 中引用的指标值与 `AudienceMetrics` 一致；未知 `evidenceKey` 丢弃 | 数字不一致 → 422 |
| `ReviewEvidenceValidator` | review 中引用的指标值与 `CampaignPerformanceSummary` 一致；区分相对提升/百分点 | 数字不一致 → 422 |
| `ContentFactValidator` | 文案中的优惠事实以服务端草稿为准，前端/LLM 不得覆盖 | 事实冲突 → 422 |

## 7. 变更流程

新增/修改指标口径时，必须同步：
1. 更新本字典；
2. 更新对应 `AiFieldRegistry` 字段注册；
3. 增补 `InsightEvidenceValidator` / `ReviewEvidenceValidator` 的边界用例；
4. 在 `CampaignReviewFlowTest` / guardrail 单测中覆盖新口径。
