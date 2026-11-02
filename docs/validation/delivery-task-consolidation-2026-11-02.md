# Delivery 数据模型精简验收报告（2026-11-02）

## 基线与工作区

PR #23 已合并，main 基线为 6c89c900e85098d381844fff5cc59b8c613baf34。从最新 origin/main 建立 codex/delivery-task-consolidation 独立 managed worktree；原 main 工作区和其他分支均未修改。没有访问或变更生产数据库；没有合并新 PR。

## A. 数据模型

旧：campaign 1:N delivery_task 0:1 delivery_record。新：campaign 1:N delivery_task；click_event / attribution_record 仍以原 taskId 关联。IN_APP/PUSH 的渠道业务表和 business_key 保留。两个实体/Mapper 中的重复结果实现已删除；只有任务作为单次发送结果权威。

只增加 sent_at 和 first_send_attempt_at，分别用于成功事实时间与单任务发送尝试统计；没有复制旧独立结果 ID、用户/活动/渠道副本或结果创建时间。保留 PENDING、PROCESSING、SENT、WAIT_RETRY、FAILED、CANCELLED，以及独立 Kafka 派发状态。完整字段表和前后 ER 图见 [设计](../delivery-task-consolidation.md)。

## B. 可靠性

- 事务提交后发布 Kafka，派发确认失败由既有 outbox 扫描补齐；Kafka 消息只作为 taskId 唤醒，领取后重读数据库。
- 条件领取和处理时间约束抵抗重复/并发/过期消费者；频控业务拒绝不生成发送尝试，Redis 故障等待重试。
- IN_APP/PUSH 唯一业务键保留；已发送但任务写库失败/进程退出时，先恢复渠道原成功时间，再考虑额度与重试上限。sent_at 不会被恢复时间覆盖。
- EMAIL 仍为模拟，结果不确定时 FAILED/EMAIL_SEND_OUTCOME_UNKNOWN 且 sent_at=NULL，不自动再发；没有 SMTP 或外部精确一次保证。
- 真失败等待五分钟；上限五次；超时/缺领取时间的 PROCESSING 可以重新进入任务派发。数据归属冲突终止并记录原因，不阻塞其他恢复项。

## C. 业务正确性与合同

可信服务端来源、VALID/REJECTED/PENDING、source_event_id 唯一约束、MARKETING_CLICK 补偿及事务回滚保留。有效 SQL 只使用任务的用户、SENT 与非空 sent_at<=click_time，先过滤后取 Last-Touch；保留 24h、5min、归因幂等/并发领取、Redis 恢复和 PENDING 阻止提前结束。历史无来源点击不自动授信。

SENT 仍是单任务发送尝试数，含失败，排除未尝试；DELIVERED 是真实成功。CLICKS/CONVERSIONS 去重人数、ATTRIBUTED_CONVERSIONS 记录数以及 CTR/CONVERSION_RATE 的 Java 公式、精度与只读隐私边界不变。历史 Agent 发送期间/日分组沿用旧时间标记；新渠道失败首次进入可见的尝试数、跨日重试修订首次尝试日结果、以及 Dashboard 成功时间纠正的边界已明确记录，不作静默变更。

详情保留 record 展示包络，record.id=null，状态/错误/发送时间投影自任务；前端类型和演示数据同步。没有伪造已删除的独立结果 ID。完整合同说明见 [设计中的指标说明](../delivery-task-consolidation.md#click归因和指标)。

## D. 数据迁移

新增 Java Flyway V9__consolidate_delivery_task；V1–V8 历史文件无变化。

确定成功提升任务滞后状态，优先采用渠道原时间；旧失败保留错误和生命周期；匹配渠道唯一事实补回旧双写缺失，单独报告恢复数；旧结果时间承接历史 Agent 分组。先预检、再回填事务与数量/逐行核验、最后删旧表。缺时间、孤儿、归属冲突、矛盾成功、无证据 SENT、EMAIL 不确定结果都阻止清理，输出最多 20 个样例 ID。

真实 MySQL 历史样本：3 条旧结果（2 成功、1 失败）和 1 条渠道唯一成功，迁移后 4 个尝试任务、3 个成功；未尝试/取消任务不计入。旧可信/无来源点击及归因 ID 保留，重复 migrate 为零、validate 通过。九种不安全历史应拒绝；DDL 后回填失败、事务回滚、修复故障原因后审计 repair 并续迁另测。

未读取生产历史，不能宣称生产不存在冲突；生产冲突由 V9 拦截，需凭独立证据审计处理。必须备份并停全部旧应用、消费和调度，禁止新旧版本混跑。MySQL DDL 不整体回滚；切换后不能只回滚旧二进制。详见 [部署与回滚](../delivery-task-consolidation.md#生产切换与回滚)。

## E. 测试与交付

最终去重通过：**151 个 Java 单元 + 57 个真实依赖集成 = 208 个后端测试**；另有 **7 个前端单元 + 3 个 Playwright 浏览器 + 9 个 Python 工具测试**。最终失败、错误、跳过均为 **0**。测试数按案例去重，不重复累计复测。

| 验证 | 实际结果 |
|---|---|
| PULSEFLOW_TEST_DOCKER=true mvn -B clean verify | 151 单元 + 53 集成全通过；9 分 29 秒 |
| 最终变更定向 mvn -B verify | 151 单元复测、18 个发送恢复场景 + 11 个 MySQL 迁移场景通过；1 分 56 秒 |
| 前端 typecheck / lint / build | 全部通过 |
| 前端 Vitest / Playwright | 7 / 3，通过 |
| Python unittest discover testing/functional | 9，通过 |
| Flyway | V7→V8、V8→V9、重复 migrate、validate、九种拒绝、DDL 后故障回滚/续迁通过 |

最终定向命令（PowerShell 原样保留点号参数引号）：

```powershell
$env:PULSEFLOW_TEST_DOCKER='true'
mvn -B verify '-Dit.test=DeliveryTaskMigrationIT,MarketingClickPipelineIT#brokerAck*+legacyProcessing*+channelOwnership*+actualChannelSend*+concurrentClaims*+frequencyRejection*+failedChannelAttempt*+taskWriteFailure*+crashAfterChannel*+abandonedClaim*+emailUnknown*+creationDedup*+rolledBackCreation*+unknownChannel*+expiredClaim*' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dfailsafe.failIfNoSpecifiedTests=false'
```

完整回归包含 31 个流水线案例；定向回归重测其中的发送路径，并增加 3 个最终恢复案例，合计 34 个不同流水线案例。迁移专项最终为 11 个案例。

真实闭环不预置成功发送、渠道成功或有效营销点击：ACTIVE Campaign → HTTP CONTENT_VIEW → Kafka → DecisionEngine → createDeliveryTask → Kafka → DeliveryConsumer → 渠道业务记录 → delivery_task SENT → HTTP CLICK → Kafka → user_event → click_event → HTTP ORDER_PAID → 实际等待 5 分钟 → Redis 领取 → AttributionService → attribution_record → Campaign Performance → Agent Tool HTTP 七项指标均为 1。详情 record.id=null，成功时间与任务一致。

只替换外部 XXL 管理端注册 bean；业务服务、MySQL 8.0、Kafka 3.7.0、Redis 7.2 与 HTTP 都是真实实现。触发器注入渠道插入/任务结果/派发确认/点击事实写库故障；不会把 Mock 单元测试当真实端到端。

最终覆盖：三渠道成功、Kafka 重复消息（等待提交 offset）、并发领取、频控拒绝、取消、发送失败/重试、消费者崩溃、原成功时间恢复与 CLICK 补偿、EMAIL 未知结果、事务回滚不发布、未知渠道、过期处理者、派发确认恢复、空领取时间、渠道归属冲突、完整 PR #23 点击回归、两代迁移和 Agent 时间窗聚合。

未执行：生产迁移/上线、外部 SMTP/Push、外部 XXL 管理端、k6 容量测试、Python Agent/LLM 全流程及独立 Functional CLI 真机 Replay。浏览器检查使用本地演示 API；真实业务端到端与 Agent HTTP 由上述容器测试证明。前端已有大 chunk 构建提示，与本次模型修改无关。

浏览器 QA（frontend-testing-debugging，Browser 插件未提供，使用仓库 Playwright）：

| 检查 | 结果 |
|---|---|
| URL/标题 | /deliveries，PulseFlow · 智能用户运营 |
| 内容与错误覆盖层 | 页面及详情有实质内容，无框架错误层 |
| 控制台/页面异常 | 0 |
| 交互 | 登录→Deliveries→查看详情→发送结果/点击/归因可见 |
| 视觉 | 1440×1000 截图已查看；本地证据在当前聊天附件 |
| 范围 | 桌面 Chromium；未额外做移动端/多浏览器验收 |

剩余风险：

- 未发现本次验证范围内待修复的 P0/P1 代码问题。
- P1 上线条件：停机切换、完整备份和恢复演练；不能混跑旧实例，也不能绕过 V9 冲突阻断。
- P2 能力边界：EMAIL 未知结果需要人工核验；长期依赖故障超过预算仍按既有方式恢复；明细查询容量未压测。发送结果随任务保留，联合归档需后续设计；点击仍受既有 90 天保留期约束。
- 外部依赖与用户端可信令牌、网关/Kafka 权限和既有调度仍须按 PR #23 部署合同配置。

调试过程中发现并修正了测试 fixture ID 与真实自增任务冲突、PUSH 时间未填、立即恢复时间的秒精度舍入，以及测试触发器权限。最终结果不包含这些已修复调试轮的失败；完整/定向命令和最终数量如上。

## 修改文件清单

| 文件 | 原因 |
|---|---|
| [docs/campaign-metrics-glossary.md](../../docs/campaign-metrics-glossary.md) | 更新设计、指标、部署或验收说明 |
| [docs/delivery-task-consolidation.md](../../docs/delivery-task-consolidation.md) | 更新设计、指标、部署或验收说明 |
| [docs/marketing-click-pipeline.md](../../docs/marketing-click-pipeline.md) | 更新设计、指标、部署或验收说明 |
| [docs/validation/delivery-task-consolidation-2026-11-02.md](../../docs/validation/delivery-task-consolidation-2026-11-02.md) | 更新设计、指标、部署或验收说明 |
| [docs/validation/marketing-click-2026-11-02.md](../../docs/validation/marketing-click-2026-11-02.md) | 更新设计、指标、部署或验收说明 |
| [pulseflow-design.md](../../pulseflow-design.md) | 更新设计、指标、部署或验收说明 |
| [pulseflow-web/e2e/core.spec.ts](../../pulseflow-web/e2e/core.spec.ts) | 浏览器详情兼容回归 |
| [pulseflow-web/src/api/demo.ts](../../pulseflow-web/src/api/demo.ts) | 同步可空结果 ID、演示合同和发送结果展示 |
| [pulseflow-web/src/types/api.ts](../../pulseflow-web/src/types/api.ts) | 同步可空结果 ID、演示合同和发送结果展示 |
| [pulseflow-web/src/views/Deliveries.vue](../../pulseflow-web/src/views/Deliveries.vue) | 同步可空结果 ID、演示合同和发送结果展示 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java) | 权威聚合改读任务尝试/成功事实 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebDtos.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebDtos.java) | 控制台查询、有效统计及兼容结果投影 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java) | 控制台查询、有效统计及兼容结果投影 |
| [pulseflow/pulseflow-boot/src/main/java/db/migration/V9__consolidate_delivery_task.java](../../pulseflow/pulseflow-boot/src/main/java/db/migration/V9__consolidate_delivery_task.java) | 预检、历史回填、指标核验及最终删表 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/DeliveryTaskMigrationIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/DeliveryTaskMigrationIT.java) | 真实 MySQL 旧版升级、保留与失败路径验证 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickMigrationIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickMigrationIT.java) | 真实 MySQL 旧版升级、保留与失败路径验证 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickPipelineIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickPipelineIT.java) | 真实发送/恢复、PR #23 回归与无预置闭环 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricQueryIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricQueryIT.java) | 同步单一任务事实及指标合同回归 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricServiceTest.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricServiceTest.java) | 同步单一任务事实及指标合同回归 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryServiceTest.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryServiceTest.java) | 同步单一任务事实及指标合同回归 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java) | 权威聚合改读任务尝试/成功事实 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java) | CLICK/Last-Touch 改读任务，保留可信补偿 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java) | CLICK/Last-Touch 改读任务，保留可信补偿 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryConsumer.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryConsumer.java) | 发送、提交后派发、幂等与原时间恢复 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryRecoveryService.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryRecoveryService.java) | 发送、提交后派发、幂等与原时间恢复 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryService.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryService.java) | 发送、提交后派发、幂等与原时间恢复 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryTaskPublisher.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryTaskPublisher.java) | 发送、提交后派发、幂等与原时间恢复 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/FrequencyControlService.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/FrequencyControlService.java) | 发送、提交后派发、幂等与原时间恢复 |
| [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculatorTest.java](../../pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculatorTest.java) | 同步单一任务事实及指标合同回归 |
| [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionServiceTest.java](../../pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionServiceTest.java) | 同步单一任务事实及指标合同回归 |
| [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/ClickEventServiceTest.java](../../pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/ClickEventServiceTest.java) | 同步单一任务事实及指标合同回归 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryRecord.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryRecord.java) | 删除独立发送结果实体/Mapper |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryTask.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryTask.java) | 新增真实成功时间与首次尝试标记 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/ClickEventMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/ClickEventMapper.java) | 条件状态转换、渠道读取或统一有效点击 SQL |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryRecordMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryRecordMapper.java) | 删除独立发送结果实体/Mapper |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryTaskMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryTaskMapper.java) | 条件状态转换、渠道读取或统一有效点击 SQL |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/InAppMessageMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/InAppMessageMapper.java) | 条件状态转换、渠道读取或统一有效点击 SQL |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/PushRecordMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/PushRecordMapper.java) | 条件状态转换、渠道读取或统一有效点击 SQL |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DataCleanupJob.java](../../pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DataCleanupJob.java) | 调度重试、故障恢复或删除旧清理依赖 |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DispatchRetryJob.java](../../pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DispatchRetryJob.java) | 调度重试、故障恢复或删除旧清理依赖 |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/RetryCompensationJob.java](../../pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/RetryCompensationJob.java) | 调度重试、故障恢复或删除旧清理依赖 |
| [testing/README.md](../../testing/README.md) | 更新设计、指标、部署或验收说明 |
| [testing/functional/campaign-fixture.sql](../../testing/functional/campaign-fixture.sql) | 移除运行脚本旧表依赖，统一验证/清理 |
| [testing/functional/state.py](../../testing/functional/state.py) | 移除运行脚本旧表依赖，统一验证/清理 |
| [testing/functional/validate.py](../../testing/functional/validate.py) | 移除运行脚本旧表依赖，统一验证/清理 |

共 45 个修改/新增/删除文件。历史迁移未修改；遗留旧表名称仅存在于历史迁移、迁移测试/逻辑或明确的历史/设计说明中。
