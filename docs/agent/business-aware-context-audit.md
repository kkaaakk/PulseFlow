# Context Builder 职责简化与预算修正审计

日期：2026-10-19。基线：PR #22 的 `6b9ad768e373e50cea0637dbcd4d16f962b0437a`。继续在原 `codex/pulseflow-context-builder` Worktree 修改，未覆盖其他 Worktree 或原工作区未提交内容。本文替换旧版将字节估算用于硬预算的设计说明。

## 修正结果

现在只有两层职责：

| 层 | 管理内容 | 容量/异常 |
|---|---|---|
| Business-Aware Context Builder | Run 开始时 Workspace 历史：Goal、Scope、Evidence、Hypothesis、最近 Diagnosis、对话和待查问题 | 字符软目标只筛选可选历史；完整必要关联信息允许超出，永不因此中止 Run |
| Pydantic AI UsageLimits | 同一次 agent.run 的所有模型请求、成功 Tool Call、累计输入和输出 Usage | SDK 原生 UsageLimitExceeded → 原有 BUDGET_EXHAUSTED/low 处理 |

删除 `estimate_tokens`、`context_token_budget`、`ContextBudgetExceeded`，以及 UTF-8 字节额度、固定预留 4000、累计 Usage 扣模型窗口公式、上下文超目标的硬异常。Context Builder 不再导入 SDK 异常、配置或 Usage 类型。

删除三项配置 `PULSEFLOW_AGENT_CONTEXT_TOKENS`、`PULSEFLOW_AGENT_CONTEXT_RESERVE_TOKENS`、`PULSEFLOW_AGENT_CONTEXT_WINDOW_TOKENS`。保留一项实际使用的历史长度启发式：`PULSEFLOW_AGENT_CONTEXT_TARGET_CHARS=12000`。它衡量序列化历史 JSON 的 Python len（字符数，含标签/标点），不是 Token、模型窗口或 Run 配额，也没有字符/4 等换算。默认值是可调整的内容整理目标，不代表测得的模型容量。

## 实际调用与业务保护

`Repository.create/begin_followup → load → Service._workspace → GrowthInvestigator.run`。每次 Run 在写入当前 USER 消息前创建深复制历史快照，并创建新的 SDK RunUsage；同一 Investigation 的追问继续共用调查 ID 和持久化记录，但 Run 预算独立。

`@agent.instructions investigation_context` 仅从快照按当前 Scope/版本构建历史，附加本轮授权优惠事实，检查最终 JSON 全部值和完整 Query JSON（包括键），再渲染返回。授权事实已回到 Agent 层，不再由历史 Builder 管理或裁剪。主链的 Query 键值 PII 检测、完整概念问题的安全边界全部保留。

同一 Run 的新 ToolObservation 继续由 Pydantic AI 原生消息历史传递，不回灌历史快照；Scope 切换会把快照中的旧版记录重新标为 BACKGROUND。Builder 不管理 SDK Tool Message、模型响应或 Tool Schema，不调用 Java/LLM，不改变 Hypothesis 状态。

Java 六个只读 Tool、observations.py 的结构化 Observation 转换、safe_query、Evidence 原始精度/元数据、Workspace 业务状态、Repository/MySQL、Hypothesis/Diagnosis 引用校验、Scope 隔离和 Proposal → Java Draft → 人工确认均保持原实现。

优先级保留：

- P0：当前有效 Hypothesis 及全部支持、反驳 Evidence；当前有效历史 Diagnosis 及其引用和未解决问题。两侧证据同等保护，完整 ID、Observation、查询/时间、来源、版本和 warnings 不裁切。
- P1：当前问题的 Metric、Campaign、Channel、时间相关证据，随后参考 Scope 和 Goal。只读取既有结构化查询，不反向解析 Observation，不创造事实；未知条件保持保守选择。
- P2：其他当前 Evidence、必要对话和调查进度。重复消息、当前问题和已呈现 Diagnosis 摘要不重复注入。
- P3：旧 Scope 与较低相关历史。历史假设/诊断只有与其全部引用 Evidence 一起容纳才纳入；无悬空引用，旧 Scope 不作为当前事实。

短历史中的可选信息完整纳入；更长历史按既有相关性排序、去重和完整单元选择。P0 先完整构造，超出字符软目标也原样保留；可选单元放不下时跳过并继续尝试其他单元。selection 只记录软目标和未注入数量。未进入上下文的信息不会从 Workspace/MySQL 删除。

## SDK Run Budget 与单次模型窗口

实际锁定 `pydantic-ai-slim==1.107.6`；生产工厂使用 OpenAI Chat Completions 兼容适配器（旧名 OpenAIModel，当前 SDK 的 OpenAIChatModel 别名）。未改变 Provider、模型、依赖版本或运行限制。

`create_usage_limits` 原样保留，配置默认值仍为请求 8、Tool 12、累计输入 12000、累计输出 4000。多个模型响应的 RequestUsage 由 SDK 累加到同一 RunUsage；生产代码不按文本长度重算消耗。下一次独立 agent.run 使用新 RunUsage。

SDK 请求数在请求前检查；Token 消耗来自响应，默认在响应后检查。因此累计 Token 可能在一次响应后超过阈值再停止，不是严格预扣费。请求级 max_tokens=2000 也仍是原设置，与 Run 累计输出 4000 分开。

已检查安装源码：

- `usage.py` 提供 count_tokens_before_request，但默认关闭；其作用是调用对应 Model 的计数能力。
- 当前 OpenAIChatModel 继承 Model.count_tokens 的 NotImplementedError，没有可靠的完整请求预检实现。
- OpenAIResponsesModel 实现了官方 Responses input_tokens.count，包含消息、instructions 和 tools；这属于另一接口，不能假设任意 OpenAI 兼容 Chat Provider 都支持。

本次不启用不受支持的计数、不新增 Tokenizer、不升级 SDK 或切换接口。若将来需要严格预检，须验证实际 Provider/模型支持的官方完整请求计数接口；只升级 SDK 不能保证兼容 Chat Provider 可用。

模型单次窗口包含固定/动态 instructions、当前消息、SDK 历史、Tool Schema 和协议开销，Builder 的字符软目标无法确定其 Token 数。生产中不把累计 Run Usage 当成单次窗口占用。

真实 Provider HTTP 400/413 的结构化 code=context_length_exceeded（顶层或 error 内）被归类为窗口拒绝：持久化 FAILED，摘要 Model context window exceeded.，抛出固定 model_context_window_exceeded；不映射为 BUDGET_EXHAUSTED。其他 Provider HTTP 错误仍 FAILED，抛出 model_provider_request_failed。原始响应 body、模型名称和 Evidence 不进入错误字符串；不根据自由文本猜测分类。工厂原有 max_retries=0 保留，SDK/HTTP 形状测试确认一次拒绝仅一次请求。未知 Provider 错误格式仍安全归为通用失败，不声称已识别所有供应商的窗口错误。

## 回归证据

本次本地完整回归：**397 passed, 1 skipped，59.69s**。真实 MySQL 8.0 专用临时容器执行既有迁移和完整 17 条 Evidence 持久化回归；现有 PII、Hypothesis、Diagnosis、Proposal、22-case Agent Evals 和六个 Context 对照全部执行。跳过项为未显式启用的真实付费 Provider Eval。两个警告来自现有工厂 OpenAIModel 名称在 SDK 中已弃用，本次保持工厂兼容行为。

`ruff check .`、本次 8 个 Python 文件的 `ruff format --check`、完整 `mypy src tests`（47 模块）通过。锁文件、依赖、数据库和 Java 未改。

先在未修改生产代码的 6b9ad76 上执行同一七步 fixture 轨迹，再执行修正版本。每次显式提供 RequestUsage(input_tokens=1200, output_tokens=200)，用于验证真实 SDK 聚合机制；数字是测试输入，不是 Tokenizer 或真实模型成本：

| 轨迹 | 模型请求数 | 最终累计输入/输出 | 结果 |
|---|---:|---:|---|
| 原 PR 七步轨迹 | 4 | 4800 / 800 | Context 自研公式过早 BUDGET_EXHAUSTED |
| 修正后五步 | 5 | 6000 / 1000 | COMPLETED |
| 修正后七步 | 7 | 8400 / 1400 | COMPLETED |
| 修正后八步 | 8 | 9600 / 1600 | COMPLETED，仍在原生上限内 |

七步完整路径：query_metric → compare_metric → propose_hypothesis → breakdown_metric → update_hypothesis → get_attribution_breakdown → Diagnosis；八步增加 list_hypotheses。每次请求检查实际注入的快照 ID 和字符数；同一 Scope 历史内容稳定，Usage 逐次增长，本轮新增 Evidence 不出现在动态历史中，最终引用全部合法。

原生默认限制也独立验证：

| 限制 | 测试返回的每次输入/输出 | SDK 停止位置 |
|---|---:|---|
| request_limit=8 | 100 / 20 | 8 次模型请求后禁止第 9 次 |
| input_tokens_limit=12000 | 4000 / 100 | 第 4 次响应后累计输入 16000，检测并停止 |
| output_tokens_limit=4000 | 100 / 1500 | 第 3 次响应后累计输出 4500，检测并停止 |
| tool_calls_limit=12 | 100 / 20，每次提议 2 个 Tool | 执行 12 个 Tool 后停止，第 7 次模型请求不能继续执行 Tool |

测试没有提高原生上限，也没有增加生产 Hooks、计数器或 BudgetManager。另以实际 create_model 工厂和 MockTransport 返回 HTTP 400，验证窗口分类、通用错误分类、单次请求、脱敏异常和安全持久化。

相对原 PR #22 的已保存六场景离线基线：

| 场景 | 历史字符数：原 PR → 修正后 | 关键证据召回：原 PR → 修正后 | 行为 |
|---|---:|---:|---|
| 空调查 | 791 → 752 | 无证据 | 均证据不足 |
| 小上下文 | 1287 → 1248 | 100% → 100% | 状态保持 |
| 早期 Metric | 3893 → 11668 | 100% → 100% | 保留相关性优先，软目标容纳更多可选历史 |
| 支持与反驳 | 3701 → 11869 | 100% → 100% | 两侧仍保留，均证据不足 |
| Scope 切换 | 2521 → 2482 | 100% → 100% | 历史与当前引用隔离 |
| 受保护大单元 | 未发送 → 13244 | 0% → 100% | 原 PR 0 次模型调用、过早停止；现在 1 次正常完成 |

六场景相对原 PR 的过早 Context 预算中止从 1 次降为 0；引用有效与 Scope 隔离均 100%。原始字符截断对照渲染器仍仅存在测试中，经 SDK 官方 instructions override 运行，未扩展成新评估框架。

原有 22-case Eval 仍 22/22 预期通过，引用有效率 100%、未支持数值声明率 0%，平均模型请求 2.0、Tool 1.136。FunctionModel 的默认合成用量不包含 instructions；显式 fixture RequestUsage 也不是生产计费。上下文增长、诊断一致性及证据召回一并报告，不用 Token 减少单独判定成功。

GitHub CI 使用 PR #22 当前 head 的 agent-compose、agent-python、backend-java、frontend 四项检查验证；交付时核对实际 SHA 与检查结果，不以本地测试代替 CI。

## 修改文件与剩余边界

- workspace/context.py：删除三个预算函数/异常，build 改为历史字符软目标；不再管理授权事实。
- agent/growth_investigator.py：动态 instructions 删除 Usage 运算；授权事实与既有 PII 检查保留；run 保留原生 UsageLimitExceeded，增加脱敏 Provider 失败分类。
- config.py、.env.example、README：删除三个硬 Token/窗口配置，新增一个实际使用的历史字符软目标并说明语义。
- tests/workspace/test_context.py：将硬超限测试改为完整保护/软目标继续、非 ASCII 字符、去重回归。
- tests/agent/test_context_integration.py：连续 5/7/8 请求、累计 Usage、四项原生限制、Provider HTTP 拒绝、授权和 PII 回归。
- tests/evals/test_context_comparison.py：大单元应继续，删除伪 Token 估算指标；保留六个对照。
- tests/agent/test_v5_hardening.py、tests/workspace/test_mysql_integration.py：适配 Builder 的软目标参数，原安全和持久化断言保留。
- 本审计报告更新原文件。

没有新代码文件、依赖、数据库结构、迁移、Agent、Service/Adapter/Strategy 层或额外 LLM 调用。上下文整理与预算职责比原 PR 更少，SDK Loop、证据模型、只读 Tool 和人工审批流程保持稳定。

剩余边界：P0 很多时历史可以超过软目标，最终仍可能被真实模型窗口拒绝；当前 Chat Provider 无可靠全请求预检；未知供应商窗口错误可能只能归为通用 Provider 失败；关键词和 ISO 日期仍只是保守相关性提示。真实模型/真实 Java/Azure 质量和生产成本没有由本次离线验收证明。
