# PulseFlow 简历描述与面试讲稿

> 配套：[README](../../README.md) · [演示脚本](demo-scenario.md) · [架构图见 README](../../README.md#架构总览)
>
> 简历原则：不罗列类名，用三条把"做了什么 + 怎么做 + 为什么可信"讲清楚。

---

## 一、简历项目描述（三条）

### PulseFlow — 事件驱动的智能用户运营后端

- **基于 Kafka、Redis 和 XXL-JOB 构建事件驱动的用户画像与 Campaign 决策链路**，覆盖行为接入、实时/窗口/标签三层画像、规则决策、Lua 原子频控、`dedup_key` 幂等触达和 Last-touch 归因；事件消费采用 at-least-once + 唯一索引幂等，决策层区分业务跳过与基础设施异常，后者写补偿任务由 Job 重试恢复。
- **设计 AI Campaign Copilot，将自然语言运营目标转换为受约束的规则 DSL**，通过字段白名单（`AiFieldRegistry`）、类型/范围校验、时间合法性、证据字段（`evidenceKeys`）和数字一致性校验控制模型幻觉；优惠事实以服务端草稿为准、`operatorId` 取自登录态，最终执行仍由确定性 Java 规则引擎完成，AI 不直接写业务库。
- **使用数据库 CAS 状态机防止 XXL-JOB 并发重复调用模型**，拆分可重试失败 / 数据未就绪 / 永久失败三态并配指数退避，避免把消费链路延迟误判为永久数据不足；通过 GitHub Actions + Testcontainers 验证 Flyway V1~V5 迁移与事件幂等消费，109 个测试零失败。

### 一句话定性（面试开场用）

> "这是一个有完整事件链路、确定性规则执行、AI 安全边界、并发可靠性和 CI 验证的智能用户运营后端，不是调一下大模型的练手 Demo。"

---

## 二、面试讲稿（按三条展开）

### 讲稿 1：事件驱动链路与可靠性

**核心叙事**：一条行为事件怎么变成一次触达，中间哪里会丢、怎么不丢。

> "用户在端上产生行为事件，先走 `POST /api/events` 进 Kafka。这里我选 Kafka 是因为行为事件是高吞吐、可重放的数据流，消费端用 `eventId` 做幂等去重，能兼顾吞吐和正确性。
>
> 消费侧我做成三阶段管线：第一阶段同事务写 `user_event` + 小时指标桶，用 `event_id` 唯一索引去重——重复消费触发 `DuplicateKeyException`，我**不信任 Kafka 重放 payload**，而是从 DB 加载标准事件继续后续阶段；指标桶用 `INSERT … ON DUPLICATE KEY UPDATE` 原子累加。第二阶段更新画像：实时指标进 Redis，窗口指标和标签进 MySQL。第三阶段进 `DecisionEngine` 做规则匹配。
>
> 这里有个我特别在意的设计——**异常传播契约**。决策引擎里业务跳过（规则不匹配、dedup 命中）在内部消化；但基础设施异常（DB/Redis/Kafka 失败）必须向外抛。否则 EventConsumer 会以为 Phase 3 成功，不写补偿任务，决策就静默丢了。补偿任务由 `CompensationJob` 重试。
>
> 触达前过频控。我用一段 Lua 脚本在 Redis 里原子完成'判额 + 扣减 + 重试标记'：用户日限 + 活动周限 + `freq:reserved:{taskId}` 保证重试不重复扣额。失败 fail-safe 拒绝。触达任务用 `dedup_key` 唯一索引防重复触达。最后转化事件进 `AttributionService`，24h 窗口 Last-touch 归因。
>
> Redis 我刻意分成三类用途各取所需：实时画像用 String/Hash、频控用 Lua 原子脚本、延迟任务用 ZSET。XXL-JOB 把不该在请求链路里做的重活剥离出去——窗口/日指标归集、Campaign 人群批量选择、补偿重试、延迟任务恢复，一共 10 个 Job 周期驱动。"

** anticipate 的问题**：
- *为什么不用 Flink 算窗口指标？* → 当前规模用 MySQL 聚合 + Job 周期归集够用，且可观测、可重算；引入 Flink 是过度设计，简历项目不该为规模而规模。
- *at-least-once 为什么不去升级到 exactly-once？* → Kafka 跨端 exactly-once 成本高且仍需业务幂等；我用业务唯一索引幂等更简单可靠，是更通用的做法。
- *补偿任务怎么避免无限重试？* → `max_retry` + 指数退避，超限转失败态告警。

---

### 讲稿 2：AI 安全边界（最有区分度的一条）

**核心叙事**：AI 在哪里帮忙、在哪里被截断、为什么不能让它直接执行。

> "AI 在这个项目里只在**理解与解释**层：自然语言转 DSL、人群洞察、文案生成、活动复盘。我定了一条硬规矩——**AI 永远不直接发券、不写业务库、不绕过校验**。
>
> 具体怎么做。运营输入一段自然语言，比如'筛选近7天活跃≥5天、近30天消费>500元、近3天未购买的用户，今晚8点发满300减30站内信，每24h最多触达1次'。LLM 产出的是一个 DSL 草稿，然后过一道 Guardrail 校验层，这是 Java 确定性代码，AI 过不了这关就执行不了。
>
> 校验有六道：第一，字段白名单——`AiFieldRegistry` 里只有 12 个受信字段，AI 编一个不存在的字段直接 422。第二，类型 + 范围——每个字段声明 INTEGER/DECIMAL/STRING/BOOLEAN 加 min/max，`activeDays7d` 不能超过 7 这种业务边界强校验。第三，时间合法性——`sendAt` 必须未来、必须带 offset、还要和 timezone 交叉校验。第四，频控约束——`maxTimes` 和 `windowHours` 必须 >0。第五，文案的优惠事实以**服务端草稿为准**，请求体改不了，`ContentFactValidator` 会丢弃任何提到草稿里不存在的数字的文案——AI 改不了优惠力度。第六，复盘的 `evidenceKeys` + 数字一致性校验——AI 结论里的数字必须等于 Java 算出来的数字，对不上 422。
>
> 还有一个权限细节：`operatorId` 从 Sa-Token 登录态取，请求体里的 `operatorId` 字段被忽略，防伪造。资源归属上，`requireDraftOwner` 校验草稿归属，`requireCampaignOwner` 校验 Campaign 归属，历史 `created_by=null` 数据默认拒绝——防猜 ID 越权。
>
> 校验通过后，确认创建由人触发，走的是原有的 `confirmAndCreate`，写真实 `campaign` + `campaign_rule`，之后由确定性 `DecisionEngine` 执行。**LLM 不直接产生业务副作用，所有落库由经过校验的 Java 服务完成**，AI 只产出草稿。
>
> 聚合指标这一层我也守住了——**指标由 Java 计算，AI 只解释**。比如 deliveryRate、clickRate、conversionRate 等复盘比率由 `PerformanceSummaryCalculator` 计算，人群平均消费等洞察指标由 `AudienceMetricsAggregator` 聚合，AI 拿到的是数字，它只写文字总结。这样 AI 即使幻觉也改不了指标本身。"

** anticipate 的问题**：
- *为什么不直接让 AI 调函数/Agent 执行？* → 函数调用让模型直接副作用业务系统，幻觉就是真实事故。我的设计里 AI 产出的是**可校验的数据结构**（DSL），不是动作，校验失败就 422，安全边界清晰。
- *字段白名单会不会限制太死？* → 这是 feature 不是 bug。运营字段本就该收敛，白名单同时是 prompt 上下文和校验源（`toPromptSection`），Java 和 Prompt 不维护两套字段表。
- *AI 失败了业务怎么办？* → AI 失败不阻塞 Campaign 执行——Campaign 一旦确认就归规则引擎，AI 只在创建前辅助和结束后复盘。复盘失败有重试，且指标已先算好，不丢。

---

### 讲稿 3：并发可靠性与 CI（工程可信度）

**核心叙事**：怎么防止并发重复调用模型、怎么不误判数据不足、CI 怎么保证不是纸上谈兵。

> "活动复盘这一步有个典型的并发问题：XXL-JOB 可能多实例、或多轮次扫描同一个 Campaign，如果不去重就会重复调用 LLM 烧钱。我没用 Redis 分布式锁，而是在 `campaign_ai_review` 表上做**数据库 CAS 状态机**：先 INSERT PENDING，再条件 UPDATE 抢占——`SET status='PROCESSING' WHERE status IN ('PENDING','RETRYABLE_FAILED','DATA_NOT_READY')`，只有 `affected=1` 的执行器才调 LLM，其余静默跳过。好处是状态可观测（查表就知道谁在处理）、失败重试简单（下轮 Job 自动拾取）、无额外中间件依赖。
>
> 然后我把'失败'拆成三态，这是评审反馈后收紧的。原来 AI 超时和数据不足都标 FAILED，Job 会无限重扫数据不足的活动。我拆成：`RETRYABLE_FAILED`（AI 超时/5xx，指数退避重试，超 `max-retry-count` 转 `PERMANENT_FAILED` 终态）、`SKIPPED_INSUFFICIENT_DATA`（数据不足，终态不重试）、`DATA_NOT_READY`（可重试）。
>
> `DATA_NOT_READY` 是个关键判断——`sentCount=0` 有可能不是真没数据，而是 Campaign 刚结束、消费链路还在归集。我用 `assessDataReadiness` 三态判定：`audience=0` 直接终态 SKIPPED（永远不会变）；`sentCount=0` 在 `data-ready-delay-minutes` 宽限期内标 DATA_NOT_READY 可重试，`next_retry_at = endTime + delay`；过宽限期才转 SKIPPED 终态。这样**不会把消费链路延迟误判成永久数据不足**。
>
> 还有个'AI 失败不丢指标'的设计：`PerformanceSummaryCalculator.compute()` 在 AI 调用之前就执行并持久化。AI 失败时指标已保存，复盘可后续重试，不因 AI 不可用而丢活动数据。
>
> 测试这块，我共 109 个测试，98 单元 + 11 集成。AI 模块 82 个，包括 Guardrail 测试和复盘状态机 13 个场景。核心 CDP/Campaign 模块补了 10 个高价值测试——决策引擎 6 个（PROFILE/EVENT/FREQUENCY/幂等/延迟）+ 归因 3 个 + 事件持久化 1 个。11 个集成测试里有 Flyway 迁移、事件幂等消费、AI 双模式启动。
>
> CI 我用 GitHub Actions + Testcontainers。有个坑值得讲：本地 Testcontainers 和 Docker Desktop 29.x 不兼容，IT 默认跳过，结果 CI-only 的 bug 一直藏着。我加了 enforcer profile——`GITHUB_ACTIONS=true` 时强制 `PULSEFLOW_TEST_DOCKER=true`，否则构建失败，**Docker 测试不允许被静默跳过**。第一次真实跑通时挖出 4 个潜伏 bug：flyway-mysql 版本和 flyway-core 不对齐导致 AbstractMethodError、测试配置注解用错、Redisson 自动配置类名变了导致 exclude 链不收敛、Flyway 索引改名后测试还在校验旧索引。修完之后 CI 全绿，11 个 IT 零跳过零失败。"

** anticipate 的问题**：
- *为什么不用 Redis 分布式锁？* → 状态机+CAS 更可观测、无额外依赖、失败重试天然支持。分布式锁还要处理锁续期、锁释放、宕机恢复，复杂度更高。
- *宽限期 10 分钟怎么定的？* → 配置化 `data-ready-delay-minutes`，默认 10。取决于消费链路 SLA，可按实际调。核心是"可重试 vs 终态"的拆分，具体值是运营参数。
- *为什么用 `GITHUB_ACTIONS` 而不是 `CI`？* → 本地 IDE（Trae CN）会设 `CI=true`，会误触发 enforcer，所以用更精确的 `GITHUB_ACTIONS`。

---

## 三、高频追问快答

| 问题 | 一句话答案 |
|---|---|
| 项目的最大挑战？ | AI 安全边界——让 AI 够用又不越权，关键是"AI 产出可校验 DSL 而非动作" |
| 为什么不用 Spring AI / LangChain？ | 当前只需 OpenAI Compatible 调用 + Guardrail，引入框架反而把校验逻辑藏起来；自建薄层更可控 |
| 怎么保证 AI 不编造数字？ | evidenceKeys + 数字一致性校验，AI 说的数字必须等于 Java 算的，对不上 422 |
| 事件幂等怎么做的？ | `event_id` 唯一索引 + DuplicateKey 回查 DB 标准事件，不信任重放 payload |
| 频控为什么用 Lua？ | 判额+扣减+标记必须原子，否则并发下超发；Lua 单次 RTT 完成三步 |
| AI 模块能关吗？ | `pulseflow.ai.enabled=false` 彻底关闭，双模式启动测试验证过，主链路不受影响 |
| 画像有三层会不会太复杂？ | 各管各的时效：实时（当天浏览）用 Redis、窗口（7/30天）用 MySQL 汇总、标签用 MySQL，查询路径明确 |
| 项目规模？ | 8 个 Maven 模块，109 个测试，V1~V5 Flyway 迁移，10 个 XXL-JOB |

---

## 四、避免的坑（反向讲法）

面试时主动讲"我踩过 / 避免了什么坑"比讲"我做了什么"更有说服力：

1. **"业务异常 vs 基础设施异常"不分** → 决策引擎里把 DataAccessException 往外抛触发补偿，否则静默丢决策。这是简历亮点"补偿恢复"的前提。
2. **AI 超时和数据不足都标 FAILED** → Job 无限重扫。拆三态 + `next_retry_at` 退避。
3. **`sentCount=0` 直接判数据不足** → 把消费链路延迟误判成永久不足。加宽限期 DATA_NOT_READY。
4. **本地 IT 默认跳过** → CI-only bug 藏着。enforcer 强制 CI 跑 Docker 测试，挖出 4 个潜伏 bug。
5. **`created_by=null` 默认允许访问** → 越权漏洞。历史数据默认拒绝，只允许创建者本人。
6. **flyway-mysql 版本写死** → 和 flyway-core 不对齐 AbstractMethodError。改用 `${flyway.version}` 随 BOM。
7. **`@EnableAutoConfiguration(exclude=...)` 排除链不收敛** → Redisson 改名后 exclude 失败。改用 `@ImportAutoConfiguration` 只导入数据层配置。
