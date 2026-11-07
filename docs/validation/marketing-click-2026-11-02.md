> PR #23 合并时的历史验收快照。V9 后已删除 DeliveryRecordMapper，当前模型及验收见 [Delivery 合并](delivery-task-consolidation-2026-11-02.md)。

# CLICK 业务链路交付报告（2026-11-02）

## A. 根因与方案

真实源码证实：旧 recordClick 没有生产调用；统一 CLICK 经 Kafka 只进入 user_event，归因 fixture 靠手工点击掩盖断链。其余根因包括事件事务吞异常、购买归因初始化 best effort、Last-Touch 先选后验、绩效快照永不刷新、自定义 Kafka 工厂默认失败跳过，以及归因 Redis 编码/超时领取/有界对账问题。

现由 EventPersistenceService 在同一事务调用 ClickEventService；原始行为仍进入 user_event，经过可信来源及真实发送关系校验才形成 click_event。普通 CLICK 无 taskId，只保留原始行为；候选营销 CLICK 区分 VALID、REJECTED、PENDING，后者有持久化补偿。保留统一接口、CLICK 类型及三张业务表，未增加中间件或第二套消费者。完整设计与安全边界见 [链路说明](../marketing-click-pipeline.md)。

## B. 一致性与可靠性

- 原始事件、首次小时指标、营销事实或补偿 outbox、购买归因任务共用 MySQL 事务；真实 SQL trigger 故障验证整体回滚。
- 原始 event_id、click_event.source_event_id、attribution target_event_id 分别有数据库唯一键；重报以首次数据库原始事实为准。重复消息不能提升可信标记、重置补偿次数或重复统计。
- 自定义容器工厂明确绑定持续基础设施重试；数据库恢复后 Kafka 自动重投。发送记录晚可见使用既有补偿领取/退避框架，五次有界尝试。
- 归因任务先持久化，Redis 提交后发布/对账；崩溃领取项五分钟恢复，轮转扫描避免孤儿任务饥饿，终态不复活。
- 不能自动恢复的边界：补偿基础设施故障超过五次预算需要运维修复并重投。该任务保留 FAILED，原始事件保持 PENDING，清理任务不删除它，归因不会静默提前终结。预算耗尽的发送可见性问题则有明确终态原因。

## C. 业务正确性

Last-Touch 在有效性过滤后选最近一条，24 小时窗口、5 分钟事件时间宽限、CLICK_LAST_TOUCH 模型保持。实际测试覆盖较早真实有效点击被较新无效历史点击遮蔽的回归；跨用户、未成功发送、早于发送、购买同刻/之后、超出窗口的点击不进入匹配。

CTR=去重点击人数/成功发送记录数，转化率=去重转化人数/去重点击人数，保持四位 HALF_UP 和零分母规则。新有效点击及有效关联转化共用统一 SQL 条件。compute 会重新计算并 upsert；Web 与 Agent performance 走同一计算器的只读当前事实路径，Agent Tool 的权限、响应字段和 PII 限制保留。测试把已落库快照故意改成旧零值，Agent 仍返回当前正确指标且没有写回快照。

## D. 真实测试与验收

运行环境：Windows / JDK 17 / Spring Boot 3.2.5；Docker Testcontainers 1.21.4；真实 MySQL 8.0、apache/kafka:3.7.0、redis:7.2-alpine，独立测试库和随机 localhost 端口。设置 PULSEFLOW_TEST_DOCKER=true，运行整个当前 Maven reactor 的 clean verify。业务闭环只有外部 XXL 调度器注册被禁用，业务服务与 HTTP/Kafka/MySQL/Redis 不使用 Mock。

| 类别 | 通过 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| Java 单元（boot 49 + campaign 91 + event 12） | 152 | 0 | 0 | 0 |
| Java 集成（完整 Failsafe） | 26 | 0 | 0 | 0 |
| 其中新增 MarketingClickPipelineIT | 15 | 0 | 0 | 0 |
| 其中新增 MarketingClickMigrationIT | 1 | 0 | 0 | 0 |
| Python Functional 工具单元 | 9 | 0 | 0 | 0 |

逐套件数据见 [测试结果 JSON](marketing-click-test-results.json)。上述数字只计算本次当前 reactor 实际执行的报告，不包含仓库遗留的旧模块 target 报告；子集行不重复计入总数。

15 个真实业务场景：

1. HTTP 两次真实点击→Kafka→原始/点击事实→ORDER_PAID→延迟归因领取→Last-Touch 结果→重算 Summary→Agent Tool HTTP 的 CLICKS、CTR、CONVERSION_RATE、Campaign performance→Web；重复点击/购买不重复计数。
2. 普通点击、公开伪造、错误令牌、重复授信尝试、非法 taskId、任务不存在、用户不符、终态未发送、发送前点击、非法 JSON properties；同时检查继承的时钟偏差修正语义。
3. 点击待发送回执，消费者停止/重启，补偿和重复执行后只生成一条事实。
4. 五次发送可见性重试耗尽，重复 HTTP 不重置次数。
5. 数据库点击 INSERT 故障持续超过旧 Kafka 默认预算，原始/指标均回滚，解除故障后自动成功，无需再发 HTTP。
6. 小时指标写入失败同样回滚原始事件，重放后恢复。
7. 并发重复 HTTP 只形成一份点击和一次小时累加。
8. 最新无效历史点击存在时，实际购买仍归因到更早真实有效点击。
9. 购买先到、点击待补偿时不能提前归因；发送回执可见后恢复匹配。
10. 补偿过程真实 MySQL 故障保持 PENDING，恢复后补偿成功。
11. 历史可信事件重放的点击超出 24 小时窗口，不归因。
12. 非法可选金额/时长扩展属性不阻断原始点击，指标相应数值为零。
13. 模拟 worker 崩溃留下超时 processing，真实 Redis 领取恢复并终结任务。
14. 已排队的前 100 个 PENDING 不会阻止后续孤儿任务恢复。
15. 基础设施五次失败后仍保留原始 PENDING 和 FAILED 补偿，30 天清理也不删除；修复后人工重投成功。

主闭环不预插入 click_event。仅独立的聚合 SQL 合同、历史坏数据回归、迁移兼容测试仍有明确用途的手工历史事实 fixture；它们不被当作接入闭环证明。历史窗口测试直接重放已具有可信来源的 canonical 事件以验证超出接入时钟容忍区间的日期；主闭环完整经过 HTTP 和 Kafka。归因宽限没有被缩短，补偿测试只推进测试行的 next_retry_at。

早期验证真实失败并已修复：测试代码一次方法名编译错误；自定义 Kafka 工厂未绑定错误处理器；非法 JSON 被返回 500；Redis 测试/业务清理编码不一致。最终回归没有遗留失败或跳过。未执行生产部署、生产历史补录、k6 容量压测、外部 XXL 管理端及 Python Agent/外部 LLM 的端到端调用；未将这些项目记录成 Passed。另运行的 Python 9 项是工具单元回归；Campaign 数据生成与 7 条数据的 CLI dry-run 已检查，结果明确为 NOT_RUN，不替代未运行的独立 Functional 全场景 CLI Replay。

## E. 工程审查与风险

原始 main 为 883a6ca；独立分支 codex/marketing-click-pipeline。最终 Commit SHA 与 PR 地址在交付消息及 PR head 中给出，不在本文内制造自引用提交。未经确认不合并 main。

迁移只新增 V8，保留历史点击 ID 和归因引用，NULL source 明确不授信；验证了 V7 有数据升级、源事件唯一键、重复部署和 Flyway validate。旧点击及旧归因明细不删除，但缺乏可信来源的历史关联不再进入有效营销指标。自动、安全的“全历史补录”不成立：必须有独立证据和精确审计清单，不能盲目重放所有旧 CLICK。

在本次本地范围和已列前置条件下，未发现剩余 P0/P1 链路缺陷。生产上线前仍须配置服务端专用令牌、受信上游认证、Kafka 生产权限、现有补偿调度并先停旧消费者；此 PR 不代表已经修改生产环境。公开通用事件和购买事件的原始身份仍继承上游信任合同，不能将本次营销点击令牌误解为全系统购买防伪方案。

P2/运维边界：长期故障需人工重投；历史可信证据不足不会自动补录；既有 90 天明细清理意味着实时接口不具备永久历史去重账本能力，长期报表须使用审核归档；Web 有界分页逐活动计算及大型活动任务/发送列表读取存在容量风险。新增索引和唯一查找控制关键路径，未宣称通过大规模性能压测。MySQL 多条迁移 DDL 的部分失败需核对实际 schema、备份与 Flyway 修复，不能整体自动回滚。

本次保持现有 Java/Spring Boot、Kafka、MySQL、Redis 架构、前端响应结构、Agent 聚合合同和权限边界。原有工作区干净，没有覆盖他人未提交工作。完整文件变更原因如下。

<!-- FILE_CHANGE_TABLE -->

| 文件 | 修改原因 |
|---|---|
| [README.md](../../README.md) | 链接营销点击业务及服务端上报部署要求。 |
| [docs/agent/agent-tool-contract.md](../../docs/agent/agent-tool-contract.md) | 更新绩效实时只读合同，保持权限和响应结构。 |
| [docs/marketing-click-pipeline.md](../../docs/marketing-click-pipeline.md) | 记录审查、设计、部署、补偿/补录、数据与性能边界。 |
| [docs/validation/marketing-click-2026-11-02.md](../../docs/validation/marketing-click-2026-11-02.md) | 按交付要求记录根因、证据、风险与全部文件原因。 |
| [docs/validation/marketing-click-test-results.json](../../docs/validation/marketing-click-test-results.json) | 保存本次当前 Maven reactor 实际通过数量。 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java) | 所有营销点击/转化聚合采用同一有效事实条件，保持隐私合同。 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolController.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolController.java) | 更新只读绩效端点的合同注释。 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolService.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolService.java) | 绩效读取当前只读事实，不依赖旧快照或写库。 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/GlobalExceptionHandler.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/GlobalExceptionHandler.java) | 非法 JSON 返回 400，避免记录原始解析内容。 |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java](../../pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java) | Dashboard、Campaign 和 Delivery 使用有效事实及权威去重/比率。 |
| [pulseflow/pulseflow-boot/src/main/resources/application.yml](../../pulseflow/pulseflow-boot/src/main/resources/application.yml) | 增加环境令牌配置并关闭 Mapper 原始属性的 DEBUG 日志。 |
| [pulseflow/pulseflow-boot/src/main/resources/db/migration/V8__trusted_marketing_clicks.sql](../../pulseflow/pulseflow-boot/src/main/resources/db/migration/V8__trusted_marketing_clicks.sql) | 新增来源唯一键、可信状态和必要索引，保留历史 ID，不批量授信。 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickMigrationIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickMigrationIT.java) | V7 有数据升级、历史 ID/归因保留、来源唯一约束和重复 migrate。 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickPipelineIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MarketingClickPipelineIT.java) | 15 项真实 HTTP/Kafka/MySQL/Redis/归因/指标、故障和恢复场景。 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricQueryIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricQueryIT.java) | 保留聚合 SQL 合同 fixture，标明来源与真实发送，区别于闭环测试。 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricServiceTest.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricServiceTest.java) | 适配统一关联 SQL 的事实替身，保留指标、参数化与隐私断言。 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentToolServiceTest.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentToolServiceTest.java) | 确认绩效只读当前事实且不调用快照写入路径。 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryServiceTest.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryServiceTest.java) | 保留控制台响应/分页合同，适配共享当前绩效计算器。 |
| [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/event/service/EventIdempotentConsumptionIT.java](../../pulseflow/pulseflow-boot/src/test/java/com/pulseflow/event/service/EventIdempotentConsumptionIT.java) | 原有真实 MySQL 幂等测试装配实际点击服务依赖。 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/CampaignPerformanceSummaryMapper.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/CampaignPerformanceSummaryMapper.java) | 原子 upsert 刷新绩效快照。 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java) | 真实有效事实重算；分离只读当前事实与持久化快照路径。 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java) | 有效性先过滤后 Last-Touch，保留待补偿任务，修复 Redis 编码/崩溃领取/对账饥饿。 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickDeliveryNotVisibleException.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickDeliveryNotVisibleException.java) | 区分有界发送可见性等待和基础设施异常。 |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java](../../pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java) | 营销候选分类、业务校验、安全拒绝、延迟补偿和 canonical 重试。 |
| [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculatorTest.java](../../pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculatorTest.java) | 验证共享有效事实计数、去重比率和快照 upsert。 |
| [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionServiceTest.java](../../pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionServiceTest.java) | 保持状态机、领取/重排/恢复覆盖，适配有效查询和 StringCodec。 |
| [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/ClickEventServiceTest.java](../../pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/ClickEventServiceTest.java) | 26 项分类、可信来源、非法属性、发送校验及重试行为单元测试。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/EventRequest.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/EventRequest.java) | 限制数据库字段长度与用户 ID，避免明显永久错误进入 Kafka。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CompensationTaskType.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CompensationTaskType.java) | 在现有补偿框架增加营销点击类型。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/ClickEvent.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/ClickEvent.java) | 映射 source_event_id，保留原主键。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserEvent.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserEvent.java) | 记录来源可信性、营销状态与安全原因码。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionRecordMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionRecordMapper.java) | 统一有效归因关联与去重转化统计。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionTaskMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionTaskMapper.java) | 锁定目标任务，串行处理重复归因。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignMapper.java) | 按活动锁定快照重算，避免并发旧值覆盖。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/ClickEventMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/ClickEventMapper.java) | 来源唯一键 upsert、统一有效性 SQL、有效 Last-Touch 和点击统计。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DataCompensationTaskMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DataCompensationTaskMapper.java) | 持久化点击待处理 outbox，重复上报不重置预算/领取状态。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryRecordMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryRecordMapper.java) | 按 task_id 读取唯一发送回执。 |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserEventMapper.java](../../pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserEventMapper.java) | canonical 事件行锁、营销状态更新和待补偿窗口查询。 |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/config/KafkaConsumerConfig.java](../../pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/config/KafkaConsumerConfig.java) | 显式绑定错误处理器，保留逐记录同步 ACK 和数据库失败重投。 |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java](../../pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java) | 将点击及购买任务可靠性放入首阶段事务，移除 best-effort 购买初始化。 |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/controller/EventController.java](../../pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/controller/EventController.java) | 从专用请求头向服务传递受信来源凭据。 |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java](../../pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java) | 事务内接入点击及购买任务，缩小 DuplicateKey 捕获范围，异常回滚。 |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventService.java](../../pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventService.java) | 认证来源并仅发布可信布尔标记，统一毫秒有效事件时间。 |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/TrustedEventSource.java](../../pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/TrustedEventSource.java) | 默认关闭的服务端令牌认证，不序列化凭据。 |
| [pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/consumer/EventConsumerTest.java](../../pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/consumer/EventConsumerTest.java) | 适配移入事务后的消费者依赖，保留 JSON null 语义断言。 |
| [pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventPersistenceServiceTest.java](../../pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventPersistenceServiceTest.java) | 验证 canonical 锁定读取、依赖及毫秒时间语义。 |
| [pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventServiceTest.java](../../pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventServiceTest.java) | 保留事件序列化合同，适配默认关闭的可信来源依赖。 |
| [pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/TrustedEventSourceTest.java](../../pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/TrustedEventSourceTest.java) | 3 项默认关闭、精确认证和弱配置单元测试。 |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CompensationJob.java](../../pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CompensationJob.java) | 复用领取/退避处理营销类型，区分耗尽原因，兼容旧购买事件补偿。 |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DataCleanupJob.java](../../pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DataCleanupJob.java) | 不删除仍关联 PENDING 点击的基础设施失败补偿。 |
| [testing/.env.test.example](../../testing/.env.test.example) | 增加明确仅测试用的来源令牌配置示例。 |
| [testing/README.md](../../testing/README.md) | 说明无预置点击的真实验收与来源令牌配置。 |
| [testing/functional/campaign-fixture.sql](../../testing/functional/campaign-fixture.sql) | 只预置发送前提，移除预插入 click_event 的假闭环。 |
| [testing/functional/generate.py](../../testing/functional/generate.py) | 修正 Campaign 点击任务关系并移除过期链路限制说明。 |
| [testing/functional/replay.py](../../testing/functional/replay.py) | 从环境读取测试来源令牌加入请求头，凭据不进入重放证据。 |
| [testing/functional/validate.py](../../testing/functional/validate.py) | 验收真实原始来源可信且 VALID 的营销点击。 |
