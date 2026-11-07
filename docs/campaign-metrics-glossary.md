# Campaign 指标口径字典

> 定义 `AudienceMetricsAggregator` 与 `PerformanceSummaryCalculator` 产出的每个指标的数据来源、计算方式、空值含义与精度规则。
> 
> 目的：明确 Java 业务计算的权威口径，为查询、绩效分析和未来 Agent Evidence 提供事实基础。

## 1. 通用约定

| 约定 | 规则 |
|---|---|
| 比例（rate）内部表示 | **0 ~ 1** 的 `BigDecimal`，4 位小数，`HALF_UP`。0.126 等价于 12.6%。 |
| 金额/均值 | `BigDecimal`，2 位小数，`HALF_UP`，单位元。 |
| 人数 | `long`，整数。 |
| 分母为 0 | **返回 `0`（`BigDecimal.ZERO`）**，不返回 null。 |
| "提高 X%" vs "提高 X 个百分点" | 二者不可混淆。比例差（相对提升）= (new-old)/old；百分点差（绝对）= new-old。 |
| 数据版本 | 人群预估提供 `profileDataVersion`，绩效摘要提供 `calculatedAt`，便于审计追溯。 |

## 2. 召回候选与基线

`user_profile.status=1` 表示账号启用。预览与聚合按账号主键最多取 50,000 个启用账号，执行按每批 500 个遍历。先匹配近 7 天活跃至少两天、近 30 天无支付的窗口摘要和 AND 条件，再用 MySQL 支付事实排除刚购买的候选。基线是该候选池。预览是当前快照估算，执行时还受活动状态、偏好和频控影响。

## 3. 人群洞察指标

| 指标 | 计算方式 |
|---|---|
| `audienceCount` | 符合召回条件的人数 |
| `activeRate7d` | `active_days_7d > 0` 的人数除以人群人数 |
| `averageSpend30d` | `spend_30d` 的总和除以人群人数，缺失用户按零 |
| `averageOrderCount30d` | `order_count_30d` 的总和除以人群人数，缺失用户按零 |
| `baseline.*` | 用候选池的人数和窗口快照按同样方式计算 |

无人命中时只返回 `audienceCount=0`。聚合不再输出标签比例与用零消费推断的购物车未购买率。活跃日期、稳定订单标识与金额统计的边界见 [主业务改造报告](marketing-core-convergence.md)。

## 4. 活动复盘指标

实现：`PerformanceSummaryCalculator`  
数据源：`delivery_task` / `click_event` / `attribution_record` / `campaign_performance_summary`

### 4.1 基础计数

| 字段 | 口径 |
|---|---|
| `targetAudienceCount` | `delivery_task` 中去重 `user_id` 的个数 |
| `sentCount` | `delivery_task.first_send_attempt_at` 非空的任务数（含失败，不含未尝试任务） |
| `deliveredCount` | `delivery_task` 中 `status='SENT' AND sent_at IS NOT NULL` 的任务数 |
| `clickedCount` | `click_event` 中通过可信来源、成功任务及发送时间校验的去重用户数 |
| `convertedCount` | 关联同一有效点击且用户、任务、活动一致的归因去重用户数 |
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

v1 schema 不直接支持 A/B 分变体，固定为空数组 `[]`。

## 5. 数据质量与变更

当 `audienceCount=0` 或 `sentCount=0` 时，比率为 0，不能据此推断效果。相对提升和百分点差需要分别计算。新增或修改指标时，更新本字典、`CampaignFieldRegistry`（若涉及 DSL 字段）和相应的 Java 计算测试。

## 6. Agent 内部查询的时间窗聚合

Phase 2 的 [只读 Tool API](agent/agent-tool-contract.md) 用同一计数和比率公式按时间窗聚合 `delivery_task.first_send_attempt_at`、`click_event.click_time`、`attribution_record.credited_at`。`SENT` 为发送记录数，`DELIVERED` 为成功状态记录数，`CLICKS` / `CONVERSIONS` 为窗内去重用户数，`ATTRIBUTED_CONVERSIONS` 为归因记录数。按 `CAMPAIGN`、`CHANNEL` 或 `DAY` 分组时，去重在每个分组内进行，跨组求和可能重复计入同一用户。

时间窗比率使用同窗事实，不能推断为同一批触达用户的因果转化率。比较接口由 Java 计算绝对差与相对差；基线为 0 时，相对差返回 null。实时事实聚合无固定快照版本，`dataVersion=null`。Campaign 绩效端点只读当前有效事实，不触发 `PerformanceSummaryCalculator.compute()` 的写入路径。

V9 保留旧发送事实时间窗口；新失败尝试、跨日重试和渠道时间纠正的具体边界见 [合并设计](delivery-task-consolidation.md)。
