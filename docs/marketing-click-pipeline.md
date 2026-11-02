# 营销 CLICK 链路与运维说明

## 源码审查结论

基线为 `main` / `883a6ca`，开始工作时工作区干净；已读取远程 main 并在 `codex/marketing-click-pipeline` 开发。仓库实际已有 V1–V7，新增 V8，未更改任何历史迁移。旧 `ClickEventService.recordClick(userId, taskId, source)` 没有生产调用方，也没有独立点击采集接口。`EventConsumer` 仅写原始事件和小时指标，Functional Campaign fixture 则手工插入点击，因此既有归因测试不能证明 HTTP/Kafka 闭环。

另有实际缺陷：事件事务捕获异常后返回失败可能部分提交；购买任务初始化是画像成功后的 best effort，异常只记日志；归因先取最近点击再验发送记录；汇总存在即返回；Web 降级统计使用事件次数和错误分母；Agent Campaign performance 只读陈旧快照。自定义 Kafka 容器工厂没有显式错误处理器，默认十次失败后跳过记录。归因 Redis Lua 使用 StringCodec，但领取清理使用默认 Codec；崩溃后 processing 项没有超时恢复；对账一直扫描最前 100 个 PENDING 任务。

## 调用与事务

```text
POST /api/events
  EventController → EventService（服务端认证来源、修正时钟、Kafka broker ACK）
  pulseflow.raw.events → EventConsumer
    EventPersistenceService.persist（单个 MySQL 事务）
      user_event（唯一 event_id，原始行为及处理状态）
      user_metric_hourly（仅首次事件原子累加）
      ClickEventService.recordClick（营销分类与业务校验）
        有效 → click_event（唯一 source_event_id）
        暂不可见 → PENDING + data_compensation_task / MARKETING_CLICK
        确定无效 → REJECTED + 安全原因码
      ORDER_PAID → attribution_task（同事务；提交后发布 Redis）
    Redis 画像 / DecisionEngine（保留既有 EVENT_REPLAY 失败补偿）
  CompensationJob → ClickEventService.retry（锁定并读取 MySQL 原始事件）
  AttributionTaskConsumer → AttributionService → attribution_record
  PerformanceSummaryCalculator / Web / Agent
```

`user_event` 保留统一原始行为；`click_event` 只承载营销事实，保留自增主键；`attribution_record.click_event_id` 保留真实点击和触达任务引用。普通 CLICK 缺少 `properties.taskId` 时不产生营销事实。存在该字段才校验：JSON 正整数且不溢出 Long、来源可信、任务存在且属于同一用户、任务 SENT、发送回执用户/活动/渠道匹配、回执成功且发送时间不晚于点击。可选 clickSource 必须是非空且不超过 64 字符的字符串，缺省采用任务渠道。

不捕获 MySQL 写入故障并将其当作业务拒绝。仅原始事件 INSERT 的 DuplicateKey 被处理；其他异常抛出事务代理，事件、指标、点击或补偿任务整体回滚。重复事件锁定并加载首次 MySQL 事实，不能通过重报改变用户、任务、事件时间或可信来源，也不重复累计指标。`click_event.source_event_id` 数据库唯一键负责并发兜底，插入采用幂等 upsert。幂等不依赖先查后插。

所有业务时间统一为 `effective_event_time`，精确到毫秒；点击时间直接取该值。保留原有接入时钟偏差算法（`abs(skew).toMinutes() > 5` 时改用接收时间，因此原算法按整分钟截断），不以消费/补偿执行时间代替点击时间。发送回执只有秒精度，允许 `sent_at <= click_time`。归因范围为 `[purchase_time - 24h, purchase_time)`，购买后 5 分钟宽限，模型仍为 `CLICK_LAST_TOUCH`；同毫秒点击按点击主键倒序确定顺序。

## 可信上报边界

`/api/events` 保持公开。普通浏览器可以伪造 userId/taskId，不能因此升级为可信营销事实。受信**服务端**通过 `X-PulseFlow-Event-Token` 上报，配置 `PULSEFLOW_EVENTS_TRUSTED_SOURCE_TOKEN`，至少 32 字符，最多使用 512 字符的令牌。空配置默认关闭营销提升，错误令牌也只保留原始事件。来源标记由 EventService 从请求头生成，在 Kafka 顶层传递并持久化；客户端 properties 或未知顶层字段不能覆盖它。令牌不进入 Kafka、MySQL、补偿 payload、响应或业务日志。

持有令牌的上游必须先依据其真实登录/交易上下文确定 userId 和触达任务，不能给浏览器发令牌，也不能提供一个任意转发客户端 userId/taskId 的“可信代理”。生产通过 HTTPS 和受控网络通信，Kafka 生产权限只授予受信服务；本改造没有为 Kafka 增加外部生产者的身份认证。不要复用 Agent 内部令牌。本方案是服务端来源信任，不是面向任意客户端的每用户签名点击 URL，也不能替代上游认证。

原有通用事件与 ORDER_PAID 的接入身份合同保持不变；它们作为原始业务事件的真实性仍由上游负责。需要防止任意客户端伪造购买时，应由业务服务受信上报购买并收紧生产网关的通用事件权限。此次没有将管理控制台 Sa-Token 的 operatorId 冒充业务用户身份。

## 补偿和恢复

未见成功发送回执、任务还在发送中或等待重试时，写 PENDING 原始事件与 `MARKETING_CLICK` 补偿任务，同事务提交。新类型复用现有表、`FOR UPDATE SKIP LOCKED` 领取、超时恢复和 CompensationJob；没有第二套消费机制。使用独立类型是因为既有 EVENT_REPLAY 只重放画像/决策，不能把“发送记录尚不可见”作为整条画像失败，也不应重复执行营销决策。新 payload 仅包含 canonical eventId，重试重新读取数据库，不相信补偿里的用户或属性。

最多五次补偿尝试，失败后按 60、120、240、480 秒退避；实际执行还取决于现有 XXL-JOB `compensationJob` 调度频率（每次领取一条）。重复 HTTP/Kafka 报告只确保同一补偿行存在，不重置重试预算，不抢占 PROCESSING，不复活 DONE/FAILED。可见性预算耗尽时标记 `REJECTED / DELIVERY_NOT_VISIBLE_EXHAUSTED`，补偿 FAILED，原始事件仍在。确定不存在的任务、用户不符、任务 CANCELLED/FAILED、凭据不可信、点击早于发送等直接终止，不重试。

基础设施故障不会导致 REJECTED；若五次尝试都无法完成，补偿 FAILED、原始事件仍为 PENDING。该状态需要运维修复依赖后重新投递，不能宣称无限自动恢复。清理任务不删除仍关联 PENDING 营销点击的 FAILED 补偿。示例操作必须限定到经过检查的单一 eventId：

```sql
-- 先检查 user_event、delivery_task/record 和该补偿行的安全原因码。
-- 仅用于修复基础设施故障，不用于给未知历史数据授信。
UPDATE data_compensation_task
SET status='PENDING', retry_count=0, next_retry_at=NOW(), locked_at=NULL
WHERE event_id=:audited_event_id AND task_type='MARKETING_CLICK'
  AND status='FAILED';
```

MySQL 首阶段失败时 Kafka 不提交 offset，显式绑定到自定义容器工厂的错误处理器持续重投，恢复后无需人工重发 HTTP。非法业务点击是正常终态，不会进入这个无限基础设施重试路径。HTTP 还对事件 ID/类型长度、用户 ID 和 JSON 结构做约束，避免明显永久错误进入 Kafka。可选时长/金额属性非法时保留原文并以零参与对应数值聚合，不让错误扩展属性阻断原始事件和营销点击。

购买事件先持久化归因任务，Redis 失败由 MySQL 对账恢复。宽限期内收到、但发送记录尚待补偿的点击会阻止归因提前 MATCHED/EXPIRED；继续使用既有短延迟重排。归因任务行锁与 target_event 唯一键处理并发/重复购买。Lua 领取会恢复超过五分钟的 processing 项；领取、重排、清理均使用 StringCodec；MySQL 对账按 ID 轮转有界批次，避免前 100 个已排队任务饿死后续孤儿。终态不会被复活。

## Last-Touch 与指标

`ClickEventMapper` 统一 SQL 有效性条件：原始来源可信且状态 VALID、用户和毫秒事件时间匹配、任务用户匹配且 SENT、成功回执的用户/活动/渠道匹配、发送时间不晚于点击。SQL **先过滤有效性，再按点击时间/ID 倒序取一条**。最新无效/无来源历史点击不会遮蔽较早有效点击。待补偿点击先解决，避免误把更早点击永久认作 Last-Touch。

Campaign、Web 和 Agent 共用该有效点击口径；有效转化还须通过 attribution → click → task 的用户/任务/活动一致性校验。点击人数和转化人数分别按用户去重；CTR=点击人数/成功发送记录数，转化率=转化人数/点击人数；SENT 仍为发送记录总数，DELIVERED 为成功发送记录数。四位小数、HALF_UP、零分母语义保持不变。Dashboard 保留营销点击事实次数口径，Delivery 列表只显示有效点击；历史 Attribution 明细保留供追溯，不删除旧结果。

`compute()` 每次从事实重新计算并 upsert，按 Campaign 行锁串行化写快照；定时汇总仍按既定最近 72 小时已结束活动策略运行。Web 和 Agent Campaign performance 走 `readCurrent()`，同一计算逻辑的只读路径，不依赖旧快照；Agent 不写库、不获取写锁，权限、聚合输出和 PII 边界不变。Agent response 形状保持兼容；存在活动时可直接得到当前事实汇总，无需先生成快照。时间范围查询仍是各类事实按自身时间过滤的同窗事件比率，不是触达 cohort 比率。

## V8、历史数据与部署

V8 为 click_event 增加可空 source_event_id 与唯一索引；多条历史 NULL 合法，所有旧 ID 和 attribution 引用原样保留。原始事件新增可信标记、营销处理状态/原因及 `(user_id, marketing_click_status, effective_event_time)` 索引。增加点击时间、任务/用户/点击时间、归因状态/ID 索引，复用原有用户/点击时间、事件 ID、发送 task_id 唯一索引。没有清空或重建表，没有修改 V1–V7 checksum。

没有自动将旧 CLICK 批量授信或补录。历史 click_event 缺少来源、原始 user_event 缺少可信证据时，保留历史内容但不计入新有效点击/转化指标；旧归因明细仍可追溯。不能根据 taskId 存在或客户重报同一 eventId 升级信任。只有取得独立可信发送/点击证据并完成审计的精确 eventId 清单，才能在受控运维流程中恢复来源并交由同一验证/补偿服务处理；不得以全表 UPDATE 授信。无法证明真伪则保留为历史未验证数据。

先部署 V8，再部署读写新字段的应用；不要混跑旧消费者，否则它可能抢走消息却不生成营销事实。滚动切换需先停止旧 raw-event 消费实例，再由新实例接管 Kafka offset。配置服务端来源令牌、现有补偿调度与告警后再放量。Flyway schema_history 负责重复部署只执行一次；升级测试验证 V7 有数据升级、重复 migrate 和 validate。MySQL 多条 DDL 不能整体回滚：中途失败须停止应用，检查实际字段/索引与备份，完成缺失 DDL 后再审计并 repair Flyway，不能盲目重跑或删历史数据。

## 已知边界与性能

长期依赖故障超过补偿预算需要运维重投；令牌未配置时营销提升按设计关闭；来源令牌和 Kafka ACL/网关属于生产部署前置条件。本次本地验收不会修改生产库或生产配置。

现有 DataCleanupJob 在 90 天后删除点击和发送明细。实时事实计算反映**当前保留的数据**，不能从已经删除的明细重建全历史去重人数；超出明细保留期的长期报表应使用单独归档的已审核快照，而不是把实时接口当作永久历史账本。此次没有延长保留期或建立新归档系统。

有效性 JOIN 会增加查询成本；现有/新增索引支持用户窗口、任务维度、原始来源唯一查找及补偿扫描。Web 列表会逐活动读取事实，适合当前有界分页，但大型活动的任务/发送统计仍会读取列表；海量场景需另行按实测优化 SQL 聚合或引入带失效策略的快照。此次不改变指标定义来换取性能，也未进行 k6 容量压测。

## 复现

```powershell
cd pulseflow
$env:PULSEFLOW_TEST_DOCKER='true'
mvn clean verify
```

需要 Docker。MarketingClickPipelineIT 启动真实 MySQL 8.0、Kafka 3.7.0、Redis 7.2，使用随机端口和独立数据库；只禁用外部 XXL 调度器注册，业务服务、Kafka 消费、Redis、MySQL 与 Agent Tool HTTP 均为真实实现。临时 SQL trigger 注入数据库故障；不预置有效 click_event。补偿时间测试按任务 ID推进 next_retry_at，不改变生产退避算法。主闭环保持完整五分钟事件时间宽限，通过接入允许范围内的事件时间缩短墙钟等待。

MarketingClickMigrationIT 专门检查 V7→V8 的历史兼容。AgentMetricQueryIT 保留手工事实 fixture，用于聚合 SQL 合同测试，不能用它替代业务闭环证明。Functional fixture 已移除预置点击；Replay 令牌只取环境变量，不写入证据。完整验收结果与文件清单见 [交付报告](validation/marketing-click-2026-11-02.md)。
