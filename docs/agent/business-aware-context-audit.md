# Business-Aware Context Builder 改造审计

日期：2026-10-19。审计基线：`40febc966fca791e67445441a7eaca8cf60b4d10`，并完整阅读实施开始时工作区的实际 Python 修改。最终交付基于 `origin/main@99048b862c75039154ba661d8d96e741feb41b55` 的独立 Worktree，分支 `codex/pulseflow-context-builder`；原工作区保留在 `codex/pulseflow-context-working-copy`。本次仅提交上下文改造差异，已有 DeepSeek 接入、中文注释、部署、Java、Web、文档删除等工作区修改保留，不打包提交。main 已包含 Azure 五类 PII 加固及 `loggingOptOut=True`；全部保留，不把原工作区旧版 Guardrail 覆盖到 main。

## 实际调用链与数据边界

1. 新调查由 `InvestigationService.create/start` → Repository `create` 建立新 ID；追问由 `follow_up/start/propose` → `begin_followup` → `load` 按原 ID 恢复全部记录。`_workspace` 复制 Scope、Evidence、Hypothesis、Message 等列表。Repository 和 Service 的生产实现无需改动。
2. `GrowthInvestigator.run` 检查用户问题，取得 Workspace，**在追加当前 USER 消息前**创建 `ContextBuilder` 历史快照。每个 Run 有独立 Builder，经 `AgentDependencies` 传递，没有跨调查记忆。
3. Pydantic AI `@agent.instructions investigation_context` 调用 Builder；当前 Scope/版本和剩余预算在每次回调时传入。授权优惠事实与上下文组成同一最终 JSON；该 JSON 的**全部值**以及**完整 Query JSON（含键）**经原有 `AzurePiiGuardrail.check`，通过后才返回框架。保留 main 对完整概念问题逐值检查的安全语义，格式标签不改变已验证的概念问题；旧查询的敏感键也无法隐藏。没有修改 SDK Loop 或增加 Hooks。
4. Java 六个只读聚合 Tool → `PulseFlowApiClient` 严格验证结构化 Response → 现有 `query_observation/compare_observation/performance_observation/attribution_observation/audience_observation` → `_save` 检查 Observation/元数据 → `Workspace.add_evidence` 用 `safe_query` 保存已验证选择条件 → Repository `add_evidence` → MySQL；返回带 Evidence ID 的 `ToolObservation`。
5. 本 Run 新增 Evidence/Hypothesis/消息不进入快照，由原生 SDK Tool Return/消息历史传给模型。切换 Scope 时，旧快照记录立即按新版本标为 BACKGROUND。新 Run 再从数据库完整恢复并重新选取。

本地实际核对 Pydantic AI **1.107.6**：`_agent_graph.ModelRequestNode` 在每次请求准备阶段解析动态 instructions，再准备模型请求并执行 UsageLimits；Tool Return 加入 SDK message history。代码没有依赖、调用或修改这些私有实现。

数据库 `agent_evidence` 保存 Observation、query_json、source、data_version、warnings、时间、Java query ID、scope_version 等字段，**不保存完整 Java Response JSON**。Builder 只使用现存字段，不能补回 Observation 未记录的 Java 数值；不反向解析 Observation，不重新调用 Java，不新增模型调用。

## 选择与可追溯性

- P0：当前版本且引用有效的 Hypothesis（含 OPEN/WEAKENED/REJECTED）的全部支持、反驳证据；最近一个有效 Diagnosis 的全部当前引用；假设本身、完整诊断和它的未解决问题共同保留。
- P1：按当前问题优先，随后 Scope、Goal，匹配 Metric、Campaign、Channel、时间。数据选择器用现有 Pydantic Tool 参数类型验证读取；Audience 只读取 `safe_query` 已保存的渠道。不同业务维度加权排序，时间仅作宽松重叠提示，创建先后只用于相同相关性时排序。
- P2：其他当前 Evidence、必要近期对话、旧诊断中的待查问题。当前问题不重复加入历史；原调查 Goal 和同一段 Diagnosis 摘要不重复渲染为对话。
- P3：其他历史消息、旧版本 Evidence/Hypothesis/Diagnosis。历史假设、诊断只有在全部引用证据也能完整放入时才纳入；缺失引用不会被包装成有效当前假设。

关键词仅识别明确指标别名、合法渠道、显式 Campaign ID 和 ISO 日期。自然语言相对日期、含糊条件、未知或历史查询格式不会被猜成已验证查询，也不会据此硬过滤证据。`business_context` 是相关性提示，真正条件始终以 Evidence.query 原文为准。

输出是完整 JSON 信息单元，保留 Evidence/Hypothesis ID、原始 Observation 精度、查询口径和时间、来源、版本、warnings、Java query ID、采集时间、支持/反驳关系。Hypothesis/历史 Diagnosis 明确是评估；相关性不代表因果。对话没有持久化 Scope 版本，因此统一标为 `UNVERIFIED_HISTORY`，不作为当前事实。

最近 Diagnosis 从持久化 Assistant Message 的 diagnosis 恢复：现有 Workspace 不直接持有 final_diagnosis，而 Repository.finish 在同一事务写终态和带诊断的 Assistant 消息。仅当非空引用全部属于当前 Scope，且 Finding 引用属于该 Diagnosis 引用集合时，才将其视为当前历史评估并保护引用；否则降为 BACKGROUND。无引用的旧待查问题也标为 BACKGROUND，不猜测其所属 Scope。

筛选只生成模型输入视图，不更新 Hypothesis 状态，也不删除或缩写 Workspace/MySQL 记录。现有 `evidence_ids`、Diagnosis 引用校验和 Proposal 引用/操作员授权继续生效。

## 预算、误差与超限

新增三个配置，不提高现有模型或累计 Token 限额：

| 配置 | 默认值 | 含义 |
|---|---:|---|
| `PULSEFLOW_AGENT_CONTEXT_TOKENS` | 4000 | 动态上下文文本的保守估算预算 |
| `PULSEFLOW_AGENT_CONTEXT_RESERVE_TOKENS` | 4000 | 为 Tool Schema、SDK 历史及后续 Tool/模型轮次预留输入空间 |
| `PULSEFLOW_AGENT_CONTEXT_WINDOW_TOKENS` | 32768 | 运维设定的模型窗口上限，应不高于实际模型容量 |

实际可用预算为三者最小值，且不小于零：

```text
configured_context_budget
max_run_input - used_input - fixed_text_estimate - reserve
operational_window - used_input - used_output - fixed_text_estimate - reserve - 2000
```

`fixed_text` 包括固定 instructions 和当前问题；完整授权优惠事实在 Builder 的同一 JSON 中作为必要单元计入预算，不另行拼接；2000 是现有每次请求的 max_tokens。SDK 继续限制累计输入 12000、累计输出 4000、请求 8、Tool Call 12（均为现有可配置默认值）。采用累计已用输入作为历史开销的保守界，会比仅看当前窗口更早收紧预算。

估算为 **UTF-8 字节数 B**，不是实际 Token 用量。对按 UTF-8 byte 分词的文本部分，实际 Token 数在 `[1,B]`（空文本为 0），因此可能高估至 `B-1`；不声称通用于所有供应商私有 Tokenizer。这个边界不覆盖供应商封装、Tool Schema、模型内部开销。reserve 是规划余量，不能保证每个 Run 都能执行满 8 次请求；SDK 原有 UsageLimits 仍以真实返回的累计 usage 收敛，原有设置不会精确预估下一请求。没有为计数增加 Provider 请求。

当前工作区实际模型为 DeepSeek Flash；核对日期的[官方模型文档](https://api-docs.deepseek.com/quick_start/pricing/)列出 1M 上下文。32768 是刻意较小的运维值，不等同于模型硬上限；换模型时需要核对该配置。

短上下文完整纳入；预算不足时依次选择完整高优先级单元，跳过放不下的低优先级单元并继续尝试其他较小单元。不会切 ID、数值、单位或引用边。selection 告知未注入的 Evidence/Hypothesis/消息数量；这些记录仍完整持久化。

如果 P0、Goal/Scope 等必要信息本身放不下，抛出仅含估算数字的 `ContextBudgetExceeded`，沿现有预算处理返回 `BUDGET_EXHAUSTED / INSUFFICIENT_EVIDENCE / low`，明确提示收窄 Scope 或检查保护证据，并持久化终态。若首个请求前超限，模型调用为零。不会默默放弃反驳证据或继续形成高置信度结论。

## 验证与对比

原工作区改造前：**49 passed, 2 skipped**；改造后含独立 MySQL 8.0 容器回归：**86 passed, 1 skipped**。**最终 main Worktree：387 passed, 1 skipped（58.85s）**，包含 main 已有 PII 加固测试、真实 MySQL 和全部离线 Eval。跳过的是未启用 `REAL_AGENT_EVAL=true` 的付费真实 Provider Eval。Java HTTP 和 Azure 响应使用受控 fixture；MySQL 是真实服务。

原工作区 `ruff check .`、本次改动 Python 文件 `ruff format --check`、`mypy src tests --exclude test_deepseek.py` 通过（39 个模块）。最终 main Worktree `uv sync --locked`、`ruff check .`、本次 10 个 Python 文件的 `ruff format --check`、完整 `mypy src tests` 均通过；mypy 检查 47 个模块，没有排除规则。原工作区未跟踪的 `tests/agent/test_deepseek.py` 在改造前已有 9 个 mypy 错误，保留未修改，不计作本次通过的静态检查。其他未改文件的格式基线也不批量改写。

新增测试覆盖：空调查、冷恢复追问与独立 ID、Metric/Channel/Campaign/时间及比较基准期、支持与反驳、来源/版本/警告/数值精度、旧 Scope 和非法跨 Scope 引用、保守未知条件、完整单元预算/显式超限、快照和 SDK 多 Tool 轮次、10 类历史文本字段 PII、真实 Azure 调用形状的 mock、授权事实精度和 PII、旧 Query 键中的敏感值、零额外模型调用、MySQL 完整记录。现有 Hypothesis、Diagnosis、Proposal 和迁移测试全部运行。

既有 22-case Eval Harness：前后均 **22/22 预期通过**；Evidence 引用有效率 100%，未支持数值声明率 0%，平均模型请求 2.0、平均 Tool Call 1.136，fixture 结果一致。其平均 Token 214.45 是 FunctionModel 合成统计，**不包含动态 instructions，不代表生产用量**。

新增六个对照场景复用原 FunctionModel、Telemetry、引用验证与数值 oracle；对照渲染器只存在于测试，逐字保留旧方法的输出规则，并通过 SDK 公开的 instructions override 使用同一套 Tool、输出验证、Run wrapper 与 fixture 策略。相同 fixture 策略只接受完整且当前 Scope 的证据：

| 场景 | 关键证据召回：前 → 后 | 文本字符/字节估算：前 → 后 | 诊断/行为 |
|---|---|---:|---|
| 空调查 | 无证据 | 173 → 791 | 均证据不足 |
| 小上下文 | 100% → 100% | 276 → 1287 | 均保留原诊断 |
| 早期 Metric 证据被尾部淹没 | 0% → 100% | 1564 → 3893 | 补回证据后 fixture 可诊断 |
| 支持与反驳都被尾部淹没 | 0% → 100% | 1613 → 3701 | 两侧完整召回，仍证据不足，不强推结论 |
| Scope 切换 | 100% → 100% | 507 → 2521 | 当前引用正确，旧结论仅背景 |
| 受保护大单元超限 | 完整证据均不可用 | 6000 → 不发送 | 原先切断 1 单元；现在显式超限、零模型请求 |

表中场景都是 ASCII fixture，故字符数与 UTF-8 字节估算相同。新增来源、版本、完整查询和引用关系使部分上下文更长；成功标准是召回和可信引用，不是单看 Token 减少。对照中引用有效率与 Scope 隔离均 100%；四个应保持状态的场景均一致；普通场景模型请求没有增加。零请求超限场景的“引用有效”仅指系统预算诊断携带的当前 ID，不是模型已读过证据。

这是离线检索与控制流回归，**不是实际大模型诊断质量、真实 Token 成本或生产验收**。真实模型/Azure 服务端质量、真实 Java 数据内容未在本次离线 Eval 中验证。

## 文件与边界复核

新增核心仅 `workspace/context.py`（简单 Builder、内部业务条件值对象和纯预算/渲染函数），没有 Service/Manager/Adapter/Strategy 分层。删除 `InvestigationWorkspace.context_payload/context_text`，没有创建同义转发方法。

改动生产文件：`agent/growth_investigator.py`、`agent/dependencies.py`、`domain/investigation.py`、`config.py`；配置及说明：`.env.example`、`README.md` 和本文。测试新增 `workspace/test_context.py`、`agent/test_context_integration.py`、`evals/test_context_comparison.py`，扩展现有 `workspace/test_mysql_integration.py`，将 `agent/test_v5_hardening.py` 的旧 context_payload 测试迁移到 Builder 并保留原安全断言。

无新依赖、数据库表、迁移、Java 接口或 Evidence 模型变更；无向量库、跨调查记忆、LLM 摘要、后台任务、新 Agent 框架、SDK Loop 改写。Campaign Proposal → Java Draft → 人工确认的生产代码没有改动。

剩余限制：关键词是保守排序提示；相对时间/复杂条件不强行解析；未持久化的 Java 字段不能恢复；保护单元很多时宁可显式停止；文本计数偏保守且 Schema/历史余量仍是估算。PII 保留原有本地字段与 Azure fail-closed 边界，本任务没有扩大为 SDK 全部消息/Retry 内容的安全架构重构。
