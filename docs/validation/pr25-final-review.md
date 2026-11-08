# PR #25 合并评审

## A. 审查结论与修复

起始 Base 为 `3d89446392360693835fd0b80247dc608745c810`，起始 Head 为 `c26ad47993fdef3b25a3d370c4a926507b5027d5`。审查原始差异中的 62 个文件，包括删除的标签策略、SQL 与部署脚本、Agent 和控制台兼容、生成与回放脚本、文档和测试。进一步核对事件持久化、消费提交、发送、归因与恢复任务的被调用路径。

| 分级 | 原因与修复 | 回归证据 |
|---|---|---|
| P1 | 按入库 ID 判断购物车最新状态会让迟到的旧加购恢复已取消商品。改为按有效时间排序，同一时刻取消优先。Lua 仅应用当前事实的版本，建任务用条件插入复核。过长或含冒号的商品 ID 用基于事件的键，旧格式仍可读取。 | `staleCartAddsCannotResurrectRemovedItemsAndCanonicalKeysSupportDelimiters`、`delayedCartReminderSendsOnceAndCancelsForRemoveOrCommittedPayment` |
| P1 | 旧定时执行器可能在租约失效后从已读候选创建任务。短写事务锁定执行行并验证尝试与租期，同时续租、复核账号和支付并插入任务。应用时间口径兼容旧租约。 | `leaseExpiryAndReclaimFenceMaterializationAndDisabledAccountsAreRechecked`、`scheduledPreviewAndExecutionRecheckPaidFactsAndRecoverTheSamePagedExecution` |
| P1 | 较早支付迟到后，去重查询可能返回空集合，旧正数摘要持续生效。保留组并写出零值。 | `earlierPaidFactCanResetAnExistingSameDaySnapshotToZero`、自然日窗口回归 |
| P1 | 重复事件会重置补偿预算，旧执行器按 ID 可覆盖新终态。重复写入保留原状态，结束条件包含锁定时间和尝试数，回放重新读取规范事件。退避按与领取一致的数据库时钟计算。 | `duplicateReplayKeepsClaimAndBackoffAndStaleWorkerCannotRewriteTerminalState`、`kafkaRetriesWhenCompensationInsertFailsAndReplayReadsCanonicalFact` |
| P1 | Redis Lua 后续命令错误可能保留先前写入。画像先做类型敏感读取，延迟队列先写目标再移除源。 | Redis 实时状态、领取和回收的 WRONGTYPE 注入 |
| P1 | 旧 Kafka 确认可能把新一轮待发布标为已发布。确认条件包含发布快照的重试代次与状态，Kafka 等待与确认写事务分离。 | `staleDeliveryAcknowledgementsAndClaimsCannotOverwriteTheNewAttempt`、`brokerAckWriteFailureIsRedispatchedEvenAfterTheTaskWasSent` |
| P1 | 恢复后旧消费者可能采用新任务的领取时间或提交过期渠道回执。比较原领取值，数据库渠道在持有领取行锁时写回执，独立恢复发送结果。 | 旧领取回归、并发消费、渠道成功后状态更新失败及 EMAIL 不重发 |
| P1 | 同一支付订单换事件 ID 可创建重复归因任务。V11 在新任务上约束用户与稳定订单 ID，重复支付复用已有归因义务，保留历史事实。 | `committedOrderOutboxRecoversRedisFailureAndDeduplicatesStableOrders`、V9/V10 升级 |
| P1 | 类型错误的已知 EVENT 或画像规则可落到默认放行分支。已知类型缺少必需字段时拒绝匹配。 | `malformedKnownRuleDoesNotBecomeAnUnconditionalMatch`、原有效规则回归 |
| P2 | 候选可能随着账号新增持续扩张，已转化查询逐账号发出。圈选固定账号 ID 上界并批量读取转化，Redis 读取置于写事务之外。 | 超过 500 个账号的并发圈选和 MySQL EXPLAIN |
| P2 | 7 日重算可能在默认隔离级别锁住小时源表范围。日汇总使用 READ COMMITTED。 | 日期桶回归和真实执行器触发。[MySQL 8.0 InnoDB 语句锁](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html) |
| P2 | V10、V11 的 DDL 分语句提交，索引构建耗时取决于数据量。保留 V10 内容和已有历史，以备份、停稳执行器和逐步核对为发布条件。 | 空库、V9→V10→V11 和 Flyway 重复执行 |

P0 未发现。剩余限制按下文记录，归因历史结果不重算。

## B. 验证入口

- Java 在 `pulseflow` 设置 `PULSEFLOW_TEST_DOCKER=true`，执行 `mvn -B -ntp clean verify`。MySQL、Redis 与 Kafka 用 Testcontainers。三种触发路径包含实际消息、数据库结果及恢复断言。
- Agent 在 `pulseflow-agent` 使用隔离 MySQL，执行 `uv run ruff check .`、`uv run mypy src tests` 和 `uv run pytest -q -rs`。
- Vue 在 `pulseflow-web` 执行 `npm run lint`、`npm test`、`npm run build`、`npm run test:e2e:demo` 和 `npm run test:e2e:integration`。后一项使用最终 Java 构建与隔离依赖。
- 回放工具执行 `python -m unittest discover -s testing/functional -p 'test_*.py'`。关键 MySQL 查询在迁移后的数据库执行 `EXPLAIN`。

最终提交、测试数量、执行计划结果与 Actions 链接记录在 [PR #25](https://github.com/kkaaakk/PulseFlow/pull/25)。

## C. 合并门槛

以最终 Head 对应的全量验证、CI 和 GitHub 分支状态为准。使用 GitHub Merge Commit，合并后核对 Base/Head 两个父节点、远端与 `origin/main`、文件树及独立工作树。实际合并 SHA 记录在 PR。

## D. 项目状态与部署

九个 XXL-JOB Handler、十六张核心业务表和两个业务 Kafka Topic。三路触达共用发送任务、限额预占、渠道回执与可信点击归因。[资源与链路报告](../marketing-core-convergence.md)

部署时停稳旧实例并备份数据，运行 Flyway V10 和 V11，按目标 XXL-JOB Admin 下线旧标签任务与相关子任务引用，刷新窗口摘要后恢复圈选。[Admin 下线脚本](../operations/retire-tag-recalc-xxl.sql)

- 截止点是创建任务前最后一次 MySQL 事实读视图。在途支付、移除或随后提交的变更仍可能晚于这次判断。购物车排序依赖规范有效时间，超出时钟校正规则的源事件依赖上游提供可靠顺序。
- Redis 全量丢失时，尚未落成发送任务的 DELAYED ZSET 没有完整重建来源。购买归因可以按 MySQL PENDING 行对账恢复。
- 缺少订单 ID 的历史数据按事件 ID 去重。金额仍按支付事件累计。稳定订单的新归因任务保留幂等记录，需计入表容量。
- 生产级压测、真实模型和外部发送渠道实发属于部署后的独立验收项。

## E. 面试说明

浏览事件即时匹配，购物车等到截止时间再判断，定时活动面向启用账号分批召回。MySQL 管持久事实和短事务，Redis Lua 管缓存与并发领取，Kafka 管跨阶段传递，XXL-JOB 管批量任务和补偿。队列、任务、事件和渠道各有自己的幂等约束。详细说明见 [面试说明](../demo/resume-and-interview.md)。
