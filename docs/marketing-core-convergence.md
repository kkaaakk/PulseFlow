# PulseFlow 主业务改造报告

基线是 GitHub `main` 的 `3d89446`。PR #24 的 V9 已把发送结果并入 `delivery_task`，本次以该模型为基础。V10 扩展了 `user_event` 查询能力。最终评审新增 V11，用索引支持购物车变更排序，并为新归因任务约束稳定订单身份。

## A. 范围与依赖审查

| 模块 | 保留及修改 | 停用或兼容处理 |
|---|---|---|
| 行为接入 | 核心生产者、模拟器、生成夹具和控制台筛选使用 `CONTENT_VIEW`、`ADD_CART`、`REMOVE_CART`、`ORDER_PAID`、`CLICK`。MySQL 继续按 `eventId` 持久化，首次插入和通用小时桶同事务。 | `LOGIN`、`SEARCH`、`LIKE`、`FAVORITE`、`SHARE`、`ORDER_CREATE` 仍可经原 API 接入和归档。历史查询、通用桶和已有显式配置的 EVENT 匹配继续可用。旧事件退出新统计、核心演示和新草稿。固定 v1 回放夹具保留用于兼容回归。 |
| 画像 | 浏览与加购计入活跃日期，支付事实提供订单数。保留今日浏览、购物车和最近活跃时间。消费金额保留用于展示和聚合。 | 停止更新独立登录、搜索和收藏指标。保留历史 `active_7d`、旧摘要和 `user_tag` 行。现有标签规则读取最新历史值，标签策略停止写入。 |
| Campaign 与 Agent | 事件属性规则沿用现有引擎。定时预览、聚合和执行使用同一召回谓词及规则比较。Java 校验新草稿，Agent 提案遵循同样的字段。 | 新草稿仅启用 `activeDays7d` 与 `orderCount30d` 的 AND 条件。已有 OR 草稿或使用退役字段的草稿需编辑后重新校验。已落库的规则仍可读，旧规则的 `active_7d` 名称映射到新活跃天数，旧计数行不会被读取为天数。既有非核心规则可作为定时召回的额外约束。 |
| 发送与归因 | 保留 V9 的统一发送、重试、渠道回执、可信点击、宽限期、Last-Touch 及绩效事实。演示使用 IN_APP 与 PUSH。 | EMAIL 继续遵守原有模拟发送及未知结果处理。Agent、提案和鉴权数据保留。 |

## 指标与时效

- 时间以 Java 进程的业务本地日期和规范事件时间 `effective_event_time` 为准。各 Java 节点需使用相同的业务时区和同步的时钟。最近 7 天与最近 30 天分别为 `[今天−6 日 00:00, 明天 00:00)` 与 `[今天−29 日 00:00, 明天 00:00)`。
- `DailyMetricJob` 幂等重算最近 7 日已结束的小时。`WindowMetricJob` 合并这 7 日的日桶和小时桶，包含今天尚未汇总的小时与迟到写入。按用户对出现正计数的浏览或加购日期去重，得到 0–7 的 `active_days_7d`。多次浏览或同日两种行为均只贡献一天。
- `order_count_30d` 从已持久化的 `ORDER_PAID` 计算。API 接收的 `ORDER_PAID` 代表上游报告支付成功。每个用户内，非空且不超过 256 UTF-8 字节的字符串或整数 `properties.orderId` 是业务标识，同一标识取保留历史里最早的有效支付时间。稳定标识缺失的旧客户端仍可接入，此时按唯一 `eventId` 计数，无法辨别同一真实订单的不同事件 ID。零金额支付也计入。较早的支付事实迟到且把订单移出 30 日窗口时，重新汇总会写入零值。支付身份来自上游契约，服务未对接支付结算系统。
- `spend_30d` 按 30 日小时桶的支付事件金额求和，保持金额统计含义。不同事件 ID 报告同一订单时，订单数去重，金额统计仍可能重复累计。
- 窗口批量读取限定当前自然日的摘要。跨日后旧摘要自然失效。存在活跃事实但当日快照尚未生成时，圈选等待首轮窗口汇总，保留应触发的计划槽。新活动行为在下一次窗口刷新进入候选，预览是当时的估算，应用偏好和发送频控会继续缩小实际发送人数。
- SCHEDULED 每批最多 500 个启用账号。摘要中先匹配 `activeDays7d >= 2` 且 `orderCount30d == 0`，再判断附加规则和用户偏好。建任务的 READ COMMITTED 短事务锁定本次执行租约，并按批复核账号状态和支付事实。Redis 偏好读取和 Kafka 发布在这段事务之外。已提交的支付在该查询时可见，Kafka 在途或在该读之后提交的支付存在竞争窗口。在圈选与支付复核之间跨日的批次重新执行，发送阶段沿用现有流程。

## B. 资源清单

### XXL-JOB 与 Spring

仓库注册九个 XXL-JOB Bean Handler。Admin 单独部署，应用默认连接 `localhost:8081/xxl-job-admin`，执行器为 `pulseflow-executor`，默认端口 9999。Cron、状态和失败策略保存在外部 Admin 数据库。当前工作区与验收环境没有已运行的业务 Admin 实例，表中周期是部署参考。部署时以 Admin 查询出的实际周期为准。

| Handler | 入口与职责 | 建议周期 |
|---|---|---|
| `dailyMetricJob` | `DailyMetricJob.execute`，重算日桶 | 每 5 分钟 |
| `windowMetricJob` | `WindowMetricJob.execute`，提交活跃、订单、金额快照并推进缓存代际 | 每 5 分钟，当天先于首次圈选 |
| `campaignSelectionJob` | `CampaignSelectionJob.execute`，恢复租约、创建实例、分批召回，每轮最多 10 个待执行实例 | 每分钟，跨日首轮在窗口刷新后 |
| `dispatchRetryJob` | `DispatchRetryJob.execute`，每轮重发最多 100 个待发布任务 | 每分钟 |
| `retryCompensationJob` | `RetryCompensationJob.execute`，恢复渠道已写回执、到期重试和处理超时任务，每轮最多 100 条 | 每分钟 |
| `compensationJob` | `CompensationJob.execute`，恢复领取超时并领取事件重放或营销点击校验任务 | 按吞吐设置，例如每 10 秒，积压时监控 |
| `delayTaskRecoveryJob` | `DelayTaskRecoveryJob.execute`，每轮迁回最多 500 条超过 5 分钟的延迟任务 | 每分钟 |
| `campaignPerformanceSummaryJob` | `CampaignPerformanceSummaryJob.execute`，聚合发送、点击和转化 | 每 5 分钟 |
| `dataCleanupJob` | `DataCleanupJob.execute`，清理到期终态补偿和旧点击/归因任务数据 | 每日低峰 |

Spring 仍负责进程内高频轮询。`DelayedTaskExecutor.executeDue` 每秒领取最多 50 条。`AttributionTaskConsumer.processAttributionTasks` 与 `reconcileOrphanedTasks` 分别每 30 秒领取、执行、恢复和对账。它们使用自己的 Redis 领取协议，与 Admin 调度分开。

### MySQL

| 核心表 | 职责与主要读写方 |
|---|---|
| `user_profile` | 账号状态及基本资料。圈选、预览、Web 查询按账号读取。 |
| `user_event` | 规范行为、支付、购物车变更和点击校验状态。事件持久化写，补偿、指标、Web 与最新事实校验读。V10 生成支付与购物车查询列和索引，V11 增加按有效时间和取消优先级检索的索引。 |
| `user_metric_hourly` | 事件事务累加小时桶。日汇总、窗口汇总读。 |
| `user_metric_daily` | 日汇总幂等写。窗口汇总读。 |
| `user_behavior_summary` | 窗口快照。决策、预览、聚合与用户 API 读。 |
| `campaign` | 触发配置、时间窗、状态、渠道及频控参数。决策、发送、定时 Job 和 Web 读，草稿确认及定时计划推进写。 |
| `campaign_rule` | 已落库规则。草稿确认写，决策与 Web 读。 |
| `campaign_execution` | 计划槽和重试状态。定时 Job 创建、领取、恢复和完成。 |
| `delivery_task` | 唯一触达意图、发布与最终发送事实。DeliveryService、发布器、消费者、恢复 Job 写，归因和绩效读。 |
| `in_app_message` | IN_APP 渠道回执，按业务键唯一。发送消费者写、恢复读。 |
| `push_record` | PUSH 渠道回执，按业务键唯一。发送消费者写、恢复读。 |
| `click_event` | 通过验证的营销点击。点击处理和补偿写，归因、绩效和查询读。 |
| `attribution_task` | 购买归因任务、宽限与状态。事件事务建任务，归因消费者及对账读写。V11 对新任务的用户和稳定订单标识加唯一键。 |
| `attribution_record` | 唯一归因结果。归因事务写，触达预过滤、统计和 Agent 读。 |
| `campaign_performance_summary` | 活动效果摘要。绩效 Job 与计算器写，统计查询使用摘要或相同事实口径。 |
| `data_compensation_task` | 事件重放和待确认点击的可重试义务。事件、点击、补偿与清理 Job 读写。 |

在测试库，Flyway V11 后有 16 张核心业务表及 6 张其他表。后者为 `user_tag`、`agent_investigation_owner`、`ai_generation_record`、`campaign_ai_draft`、`campaign_ai_review`、`flyway_schema_history`。Python Agent 的独立库另行管理。

### Redis

| Key | 类型与职责 | 有效期及恢复 |
|---|---|---|
| `event:processed:{eventId}` | String，画像 Lua 的处理标记 | 7 天。MySQL 事件唯一约束和任务去重仍生效，Redis 标记随待处理事件重放恢复。 |
| `user:rt:{userId}` | Hash，最近浏览或加购的有效时间 | 无 TTL，Redis 恢复依赖已有持久化或后续事件，控制台故障降级可读 MySQL。 |
| `user:daily:{userId}:{yyyyMMdd}` | Hash，今日浏览次数供 User 360 查询 | 新写入 48 小时。旧字段隔离，历史未过期键可按实际运维需要定向清理。 |
| `user:cart:{userId}` | Hash，商品项 ID 到属性 JSON | 无 TTL，从 MySQL 最新购物车事实更新，移除及支付按相同 `cartItemId` 删除。 |
| `user:cart-version:{userId}:{cartItemId}` | String，购物车事实的排序版本 | 7 天。Lua 防止较旧的回放覆盖更新状态。每次处理购物车事件时，从索引读取当时最新的事实。 |
| `user:window:{userId}`、`user:window:generation` | Hash 缓存和 String 代际，控制台读 | 画像缓存 5 分钟，按日期和代际失效。成功写入摘要后推进代际，Redis 异常回源。圈选与预览直接查 MySQL。 |
| `delay:pending:DELAYED_CAMPAIGN`、`delay:processing:DELAYED_CAMPAIGN` | ZSET，分数为到期或领取毫秒 | 无 TTL，Lua 防止同一成员重复排队与并发领取。处理超过 5 分钟退回 pending，目标写入成功后移除源任务。 |
| `delay:attribution`、`delay:attribution:processing` | ZSET，归因宽限、领取与重试 | 无 TTL，Lua 领取、超时回收、失败重排。MySQL PENDING 对账可重建。 |
| `freq:user:{userId}:{date}` | String 用户日计数 | 24 小时，日期隔离。 |
| `freq:campaign:{campaignId}:{userId}` | String 活动与用户计数 | 7 天，随新预占刷新。 |
| `freq:reserved:{taskId}` | String 任务重试预占 | 24 小时，重试优先使用已有预占。 |
| `dnd:user:{userId}`、`unsub:{channel}` | String 开关和 Set 渠道退订成员 | 无 TTL。恢复依赖 Redis 持久化或配置重建。 |

### Kafka

| Topic | 生产者与消费者 | Key、分区及提交语义 |
|---|---|---|
| `pulseflow.raw.events` | `EventService` 等待 broker 确认。`EventConsumer` 写入行为及归因义务，处理 Redis 和事件决策。组 `pulseflow-event-group`。 | `userId` 字符串，4 分区，应用配置单副本，消费者并发 4。 |
| `pulseflow.delivery` | `DeliveryTaskPublisher` 在任务提交后发布，恢复 Job 重投。`DeliveryConsumer` 领取 MySQL 任务。组 `pulseflow-delivery-group`。 | `userId` 字符串，2 分区，应用配置单副本，消费者并发 2。 |

两个监听器使用同一实际容器工厂。`enable-auto-commit=false`、`AckMode.RECORD`、同步提交。监听器正常返回才按记录提交 offset。失败可由错误处理器重新投递，业务基础设施异常使用固定 1 秒、无限次重试配置。事件后续失败仅在补偿记录成功后返回。任务 Kafka 消息用于唤醒，任务状态和内容从 MySQL 读取。

## C. 链路与恢复

### EVENT 商品浏览实时促销

`POST /api/events` → `pulseflow.raw.events` → `EventConsumer.consume` → `EventPersistenceService.persist` 提交原始事实及小时桶 → `RealtimeProfileUpdateService.update` → `DecisionEngine.evaluate` 匹配活动与启用规则 → 偏好检查 → `DeliveryService.createDeliveryTask`。重复事件以 MySQL 原始规范记录重放。画像或决策失败由 `data_compensation_task` 的 EVENT_REPLAY 继续，重放从 `user_event` 读取规范事实。重复投递保留现有补偿租约、退避和终态。发送去重键包含活动、用户和事件。

### DELAYED 加购未购买提醒

`DecisionEngine.evaluate` 使用事件有效时间加 `delay_seconds` 确定 ZSET 到期，重放保留同一任务 ID 与原到期。`DelayedTaskExecutor` 原子领取，检查活动窗口、Redis 购物车及偏好。数据库用条件插入复核原加购是否仍是该商品的最新变更。有效时间相同则移除或支付优先，其余平局按数据库 ID。触达去重键与任务 ID 一致。异常保留 processing，由超时 Job 恢复。旧格式的有效队列成员可继续领取，带冒号或过长的商品 ID 使用基于原事件 ID 的新格式。

Redis 整库丢失时，DELAYED 尚未生成 `delivery_task` 的队列没有 MySQL 对账来源。已有补偿义务或 Kafka 重放可以重新调度对应事件，不能保证恢复所有已提交 offset 的延迟任务。Redis 与 MySQL 短暂不同步时，条件插入拒绝已见到取消事实的旧加购。Redis 条目缺失时选择取消。相同购物车项的事件需使用同一个 `cartItemId`，队列持久化采用部署环境已有的 Redis 保障。支付或移除尚在途，或在最终插入的读视图之后提交时，仍有时间差。

### SCHEDULED 近期活跃未购买召回

`CampaignSelectionJob.execute` 以版本条件推进下一触发时间，同事务写入 `campaign_execution`。活动没有 cron 时只执行一次。有 cron 时按表达式推进。唯一活动和计划槽避免重复建实例。PENDING → RUNNING 以条件更新领取。圈选在固定账号 ID 上界内分页。`DecisionEngine.evaluateBatch` 用窗口快照和偏好产生候选，写事务锁定并核对当前尝试及租期，再复核账号和支付，写入当页任务并续租。任务以执行 ID 与用户 ID 去重。成功 → DONE，异常或 10 分钟租约过期 → PENDING 重扫，耗尽三次重试后 → FAILED。租约过期或已被接管的尝试无法写入新任务和覆盖执行状态。

### 统一发送、点击、归因

三路创建 `PENDING/PENDING` 的发送任务。提交后按用户分区发布，发布失败留待 `dispatchRetryJob`。迟到的确认以重试代次和发布时状态为条件，避免覆盖新一轮待发布。`DeliveryConsumer` CAS 领取 PROCESSING，优先核对既有渠道回执，再执行 Redis Lua 频控并记录新预占。任务进入 SENT、WAIT_RETRY、CANCELLED 或 FAILED。IN_APP/PUSH 在短事务内检查领取令牌并用唯一渠道业务键写回执。渠道成功而状态未更新时可从回执恢复发送时间。EMAIL 仍为模拟发送，已尝试但结果未知时进入失败终态。

可信 CLICK 必须来自带机器凭据的入口，与用户及已发送任务匹配且点击时间不早于发送。暂未看到发送状态时创建点击补偿。`ORDER_PAID` 的归因任务与原始事件同事务提交。新任务按用户和稳定订单 ID 去重并保留这条幂等记录。已存在的历史结果原样保留。缺少订单 ID 的事件沿用目标事件 ID。`AttributionTaskConsumer` 在 5 分钟宽限后从过去 24 小时内选最近有效点击，归因写入和任务状态更新同事务。DB 对账可补回遗失的 Redis 归因队列，结果由绩效 Job 汇总。

## D. 迁移、发布与验证

1. 在 XXL-JOB Admin 停止 `tagRecalcJob` 并停稳该任务，核对是否有其他作业把它配置为子任务。停掉 Admin 调度节点后也可对其独立数据库运行 [下线 SQL](operations/retire-tag-recalc-xxl.sql)。脚本保留定义和历史，仅将目标执行器的 Bean 任务置为停止。停用字段依据 [XXL-JOB 2.4.1 Admin 实现](https://github.com/xuxueli/xxl-job/blob/2.4.1/xxl-job-admin/src/main/java/com/xxl/job/admin/service/impl/XxlJobServiceImpl.java)。检查实际周期，确保跨日后先刷新窗口。
2. 停稳旧消费实例和执行器并备份，再执行 Flyway 到 V11。V10 保留旧摘要并扩展金额精度，V11 调整购物车检索索引并增加归因订单键。索引构建的 IO、耗时和元数据锁需要按实际 `user_event` 规模评估。保留 Kafka offset、Redis 队列和频控。MySQL DDL 按语句提交。迁移中断后先核对实际 schema 和 Flyway 历史，再按备份恢复流程处置。
3. 首次刷新窗口后启用定时圈选。核对九个 Handler、校验已支付排除，并用归档和渠道结果追踪一条三模式样例。切换旧草稿前重新预览。外部 Admin 的部署变更需按目标环境执行。

额外修复包括 Redis 读写与 Lua 编码错配、队列领取所有权、旧加购误触达、计划槽推进与实例写入不原子、ONCE 活动误重复，以及规则读取的基础设施异常被当作不匹配。

最终评审的问题清单、验证命令及部署边界见 [PR #25 合并评审](validation/pr25-final-review.md)。
