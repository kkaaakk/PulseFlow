# PulseFlow Agent 源码学习指南

> 适用目录：`pulseflow-agent/`\
> 学习目标：理解 Pydantic AI Agent 的核心架构、Tool Calling、强类型领域对象、Workspace 持久化、Java 业务 API 集成与安全边界。

---

## 1. Agent 模块整体结构

```text
pulseflow-agent/
│
├── pyproject.toml
├── .env.example
├── Dockerfile
├── alembic.ini
│
├── migrations/
│   └── versions/
│
├── src/
│   └── pulseflow_agent/
│       │
│       ├── main.py
│       ├── runtime.py
│       ├── config.py
│       │
│       ├── domain/
│       │   ├── investigation.py
│       │   ├── campaign_proposal.py
│       │   └── contracts.py
│       │
│       ├── clients/
│       │   └── pulseflow_api.py
│       │
│       ├── agent/
│       │   ├── dependencies.py
│       │   ├── instructions.py
│       │   ├── growth_investigator.py
│       │   └── observations.py
│       │
│       ├── workspace/
│       │   ├── models.py
│       │   ├── repository.py
│       │   └── service.py
│       │
│       ├── security/
│       │   └── pii_guardrail.py
│       │
│       └── observability/
│           └── tracing.py
│
└── tests/
```

整个 Agent 可以先理解成：

```text
Frontend
   ↓
main.py
   ↓
runtime.py
   ↓
Growth Investigator Agent
   ↓
Tool Calling
   ↓
AgentDependencies
   ↓
PulseFlowApiClient
   ↓
Java Business API
   ↓
业务数据
```

Agent 自己负责分析与决策路径，Java 负责权威业务数据和最终业务规则。

---

# 2. 推荐学习顺序

建议不要从 `main.py` 开始，也不要直接逐行阅读 `growth_investigator.py`。

推荐顺序：

```text
domain
  ↓
clients
  ↓
dependencies
  ↓
instructions
  ↓
growth_investigator
  ↓
observations
  ↓
workspace
  ↓
campaign proposal
  ↓
security
  ↓
runtime
  ↓
main
```

核心原则：

> 先理解 Agent 在处理什么对象，再理解 Agent 能调用什么能力，最后再看整个 Agent 是怎么被运行起来的。

---

# 3. 第一阶段：学习 `domain/`

优先看：

```text
src/pulseflow_agent/domain/investigation.py
src/pulseflow_agent/domain/campaign_proposal.py
src/pulseflow_agent/domain/contracts.py
```

这一阶段主要理解领域对象。

核心关系可以先记成：

```text
Investigation
    ↓
Hypothesis
    ↓
Evidence
    ↓
Diagnosis
    ↓
CampaignProposal
```

## 3.1 Investigation

表示一次完整的业务调查。

例如：

> 最近召回 Campaign 转化率下降了，帮我查一下原因。

这就可以对应一次 `Investigation`。

它负责承载：

- 调查主题
- 调查状态
- Evidence
- Hypothesis
- Diagnosis
- Proposal
- 用户/Operator 归属

---

## 3.2 Hypothesis

表示：

> Agent 当前怀疑的原因。

例如：

```text
转化率下降
可能原因：
- CTR 下降
- 某个渠道流量异常
- Attribution 异常
```

这些都可以作为 Hypothesis。

要注意：

```text
Hypothesis ≠ 已证实事实
```

它只是“待验证判断”。

---

## 3.3 Evidence

表示：

> Agent 通过 Tool 查询得到的业务事实。

例如：

```text
过去 7 天 CTR = 2.1%
前一周期 CTR = 3.0%

CHANNEL=SMS CTR 下降 35%
```

这些才属于 Evidence。

所以一定要区分：

```text
Hypothesis
= 我怀疑什么

Evidence
= 我查到了什么
```

---

## 3.4 Diagnosis

表示：

> Agent 综合 Evidence 后得到的诊断结论。

例如：

```text
本次转化下降主要由 SMS 渠道 CTR 下滑导致，
Attribution 数据没有明显异常。
```

因此：

```text
Evidence
   ↓
综合分析
   ↓
Diagnosis
```

---

## 3.5 CampaignProposal

表示：

> 基于 Diagnosis，Agent 建议下一步采取什么运营动作。

例如：

```text
降低 SMS 渠道占比
增加 Push 渠道
调整目标用户人群
修改发送时间
```

因此完整逻辑是：

```text
发现问题
↓
Investigation
↓
Hypothesis
↓
Evidence
↓
Diagnosis
↓
CampaignProposal
```

---

## 3.6 MetricQuery / SegmentFilter

这些对象属于 Tool 的强类型参数。

例如 Agent 查询指标时，不应该直接随便传：

```python
{
    "metric": "...",
    "start": "...",
    "filter": "..."
}
```

而是使用类似：

```text
MetricQuery
SegmentFilter
```

这样的 Pydantic Model。

它的价值是：

```text
LLM Tool Args
↓
Pydantic Validation
↓
Java API
```

防止模型生成不合法参数。

---

# 4. 第二阶段：学习 `clients/pulseflow_api.py`

重点文件：

```text
src/pulseflow_agent/clients/pulseflow_api.py
```

这是 Agent 与 Java PulseFlow 业务系统之间的桥梁。

整体链路：

```text
Agent
↓
Tool
↓
PulseFlowApiClient
↓ HTTP
Java Internal Agent Tool API
↓
Java Service
↓
MySQL / Redis
```

重点找这些能力：

- 指标查询
- 指标对比
- 维度拆解
- Attribution Breakdown
- Campaign Performance Summary
- Campaign DSL Validation
- Audience Preview
- Proposal / Draft 相关接口

这里不需要重点研究 HTTP 库怎么写。

真正应该理解的是：

> Python Agent 不直接访问 Java 的业务数据库。

也就是说不要变成：

```text
Agent
↓
直接查 MySQL
```

而应该：

```text
Agent
↓
Java Business API
↓
Java Business Service
↓
Database
```

这样 Java 仍然是业务规则和数据口径的 Source of Truth。

---

# 5. 第三阶段：学习 `agent/dependencies.py`

重点文件：

```text
src/pulseflow_agent/agent/dependencies.py
```

这里主要理解：

```text
AgentDependencies
```

它本质上是 Agent 每次运行时能够访问的一组外部依赖。

可以概念化理解成：

```text
AgentDependencies
├── PulseFlowApiClient
├── WorkspaceService
├── Current User / Operator
├── Config
└── Runtime Resources
```

Pydantic AI 的 Tool 可以通过：

```python
ctx.deps
```

使用这些依赖。

概念上：

```text
Agent
↓
RunContext[AgentDependencies]
↓
ctx.deps.xxx
```

要记住：

> Dependencies 是 Agent 的运行环境，不是 Agent 的业务结果。

这样 Tool 就不需要自己到处创建：

```text
HTTP Client
Database Connection
User Context
Config
```

而是统一注入。

---

# 6. 第四阶段：学习 `agent/instructions.py`

重点文件：

```text
src/pulseflow_agent/agent/instructions.py
```

这里主要看 Agent 的 Prompt / Instructions。

例如定义：

```text
你是谁
负责什么任务
什么时候查询数据
如何使用 Evidence
什么时候输出 Diagnosis
哪些事情不能做
```

学习时不要逐句背 Prompt。

重点区分两种约束。

## 6.1 适合放 Prompt 的约束

例如：

```text
优先基于 Evidence 做判断
不要在缺少数据时直接下结论
发现异常后继续寻找原因
```

这些属于模型行为指导。

---

## 6.2 不能只靠 Prompt 的约束

例如：

```text
禁止越权访问用户数据
禁止读取 PII
禁止修改不属于当前用户的 Proposal
```

这些必须靠代码和业务系统保证。

也就是：

```text
Prompt
= 行为约束

Java / Python Code
= 安全约束
```

---

# 7. 第五阶段：重点学习 `growth_investigator.py`

重点文件：

```text
src/pulseflow_agent/agent/growth_investigator.py
```

这是 Agent 最核心的文件。

不要一次逐行读完。

建议分三遍。

---

## 7.1 第一遍：只找 Agent 定义

重点看类似：

```python
Agent(...)
```

关注：

```text
model
deps_type
output_type
instructions
```

例如概念上：

```text
Agent[
    AgentDependencies,
    Diagnosis
]
```

可以理解成：

```text
运行依赖：
AgentDependencies

最终结构化输出：
Diagnosis
```

这一遍只需要搞懂 Agent 的输入环境和最终结果。

---

## 7.2 第二遍：只看 Tools

把所有 Tool 找出来。

例如可能包括：

```text
compare_metric
get_metric_breakdown
get_attribution_breakdown
get_campaign_performance
...
```

每个 Tool 都建议整理成：

```text
Tool
├── 输入
├── 调用哪个 Java API
├── 返回什么
└── 最终形成什么 Evidence
```

例如：

```text
compare_metric
↓
MetricQuery
↓
PulseFlowApiClient
↓
Java
↓
Metric Result
↓
Evidence
```

这样会比逐行看 Python 更容易理解。

---

## 7.3 第三遍：重点看 Agent 如何动态选择 Tool

这是整个项目最重要的 Agent 能力。

例如用户输入：

```text
最近召回 Campaign 转化率下降了，帮我查原因。
```

Agent 不一定固定走：

```text
A
↓
B
↓
C
↓
D
```

而可能：

```text
compare CTR
↓
CTR 异常？
├── 是
│   ↓
│ breakdown CTR by CHANNEL
│
└── 否
    ↓
 compare conversion
    ↓
 conversion 异常？
    ↓
 get_attribution_breakdown
```

不同数据情况下，Tool 轨迹不同。

例如：

### 场景 A：CTR 下降

```text
compare CTR
↓
compare conversion
↓
breakdown CTR by CHANNEL
```

### 场景 B：Conversion 下降

```text
compare CTR
↓
compare conversion
↓
get_attribution_breakdown
```

这部分体现的就是：

> LLM 根据 Tool Result 和已有 Evidence，自主决定下一步调查路径。

这也是它与固定 Workflow 最主要的区别。

---

# 8. 第六阶段：学习 `agent/observations.py`

重点文件：

```text
src/pulseflow_agent/agent/observations.py
```

可以把它理解成：

```text
Java API 原始结果
↓
Observation
↓
Evidence / Agent Context
```

Java API 可能返回比较底层的数据结构。

例如：

```json
{
  "metric": "conversion_rate",
  "current": 0.021,
  "baseline": 0.031
}
```

Agent 不一定应该直接把所有原始 JSON 当成业务 Evidence。

所以 Observation 层负责把结果整理成：

```text
模型更容易理解
可以持久化
可以继续推理
可以形成 Evidence
```

的结构。

---

# 9. 第七阶段：学习 `workspace/`

重点目录：

```text
src/pulseflow_agent/workspace/
```

推荐顺序：

```text
models.py
↓
repository.py
↓
service.py
```

---

## 9.1 models.py

看数据库持久化模型。

重点观察：

```text
Investigation
Evidence
Hypothesis
Diagnosis
CampaignProposal
```

哪些东西真正落数据库。

---

## 9.2 repository.py

这里负责数据访问。

主要理解：

```text
保存 Investigation
查询 Investigation
保存 Evidence
更新状态
保存 Proposal
读取历史调查
```

不要一开始纠结 SQLAlchemy 的细节。

先理解：

```text
Repository
= Database Access Layer
```

---

## 9.3 service.py

这里是 Workspace 的业务层。

主要看：

```text
创建 Investigation
追加 Evidence
更新 Investigation 状态
保存 Diagnosis
保存 Proposal
恢复 Investigation
```

这里最重要的概念是：

```text
Agent State
≠
LLM Context
```

如果所有东西只存在 LLM Context：

```text
服务重启
↓
调查状态丢失
```

而 Workspace 持久化后：

```text
Investigation
Evidence
Hypothesis
Diagnosis
Proposal
↓
Database
```

即使 Agent 服务重启，也可以恢复。

这也是：

```text
Demo Agent
↓
Production Agent
```

的重要区别之一。

---

# 10. 第八阶段：学习 Campaign Proposal

重点结合：

```text
domain/campaign_proposal.py
```

以及：

```text
migrations/versions/0002_campaign_proposals.py
```

理解 Proposal 生命周期。

整体流程：

```text
Diagnosis
↓
CampaignProposal
↓
Agent DB 持久化
↓
用户查看 Proposal
↓
Java 校验 owner / Investigation / Evidence
↓
生成 Campaign Draft
↓
人工确认
↓
Campaign
```

要重点理解：

```text
Agent
= 建议

Java
= 最终业务执行
```

也就是：

```text
LLM 不直接成为业务 Source of Truth
```

最终 Campaign 创建、DSL 校验、权限判断等确定性业务规则仍由 Java 控制。

---

# 11. 第九阶段：学习数据库迁移

重点目录：

```text
migrations/versions/
```

优先看：

```text
0001_agent_workspace.py
0002_campaign_proposals.py
```

数据库 Schema 往往是理解 Agent 数据模型最快的方法。

可以从数据库反推：

```text
Agent 到底需要保存什么？
哪些对象有生命周期？
哪些字段需要追踪 owner？
Proposal 和 Investigation 怎么关联？
Evidence 怎么关联？
```

不要一开始深入 Alembic 命令细节。

先理解数据模型。

---

# 12. 第十阶段：学习 `security/pii_guardrail.py`

重点文件：

```text
src/pulseflow_agent/security/pii_guardrail.py
```

主要理解：

```text
什么数据允许进入模型
什么数据不能进入模型
什么数据必须提前拦截
```

核心原则：

```text
错误做法：

Prompt：
“请不要泄露 PII”
```

更可靠的做法：

```text
业务数据
↓
PII Guardrail
↓
过滤 / 阻断
↓
LLM
```

也就是：

> 安全不能只依赖模型听不听话。

---

# 13. 第十一阶段：学习 `runtime.py`

重点文件：

```text
src/pulseflow_agent/runtime.py
```

这里可以理解成：

> Agent 的组装层。

它负责把之前分散的零件组合起来。

例如：

```text
Config
↓
PulseFlowApiClient
↓
WorkspaceService
↓
AgentDependencies
↓
Growth Investigator Agent
```

因此：

```text
runtime.py
= Dependency Wiring / Runtime Assembly
```

看到这里以后，你应该已经认识所有被组装的组件，所以阅读会很轻松。

---

# 14. 第十二阶段：最后看 `main.py`

重点文件：

```text
src/pulseflow_agent/main.py
```

这是整个 Python Agent 服务的 HTTP 入口。

主要理解：

```text
Frontend Request
↓
main.py
↓
创建 / 恢复 Investigation
↓
runtime
↓
Agent.run(...)
↓
Tool Calls
↓
Workspace
↓
SSE / HTTP Response
```

这一层重点看：

- Investigation API
- SSE
- 取消任务
- 恢复任务
- 请求鉴权
- Runtime 调用

不要把精力主要放在 FastAPI Route 写法上。

更重要的是：

> 一次用户请求是怎样真正进入 Agent Runtime 的。

---

# 15. 最终完整运行链路

学习完之后，脑子里应该形成这条完整链路：

```text
用户
↓
pulseflow-web
↓
Agent HTTP API
↓
main.py
↓
runtime.py
↓
Growth Investigator
↓
LLM 判断下一步
↓
Tool
↓
AgentDependencies
↓
PulseFlowApiClient
↓
Java Internal Tool API
↓
Java Business Service
↓
MySQL / Redis
↓
查询结果
↓
Observation
↓
Evidence
↓
Workspace 持久化
↓
LLM 继续分析
↓
Hypothesis
↓
更多 Tool Calls
↓
Diagnosis
↓
CampaignProposal
↓
Proposal 持久化
↓
Java 校验
↓
Campaign Draft
↓
人工确认
↓
Campaign
```

---

# 16. 最应该优先吃透的 6 个文件

如果时间有限，优先级如下：

```text
1. domain/investigation.py
2. agent/dependencies.py
3. clients/pulseflow_api.py
4. agent/growth_investigator.py
5. workspace/service.py
6. main.py
```

这六个文件分别对应：

| 文件 | 要解决的问题 |
|---|---|
| `investigation.py` | Agent 到底在处理什么业务对象？ |
| `dependencies.py` | Tool 能使用哪些运行时能力？ |
| `pulseflow_api.py` | Agent 怎么访问 Java 业务系统？ |
| `growth_investigator.py` | Agent 怎么调用 Tool、怎么动态调查？ |
| `workspace/service.py` | Agent 状态怎么持久化？ |
| `main.py` | 用户请求怎么真正进入 Agent？ |

吃透这 6 个文件，基本就能把整个 Agent 的核心架构讲清楚。

---

# 17. 暂时不用深入的部分

第一轮源码学习阶段，可以降低优先级：

```text
Dockerfile
Tracing 具体实现
Alembic 命令细节
HTTP Client 底层封装
FastAPI Route 语法
测试辅助代码
配置读取细节
```

这些属于工程外围。

应该先把：

```text
Domain
Tool
Dependencies
Java API
Workspace
Agent Decision
```

搞清楚。

---

# 18. 学习源码时建议问自己的问题

每看完一个模块，尝试脱离源码回答：

1. 用户的一句话在哪里进入 Agent？
2. `Investigation` 是什么？
3. `Hypothesis`、`Evidence`、`Diagnosis` 有什么区别？
4. 为什么 Tool 参数要使用 Pydantic Model？
5. `AgentDependencies` 是什么？
6. Tool 为什么不直接查询 MySQL？
7. Python Agent 和 Java 之间怎么通信？
8. Agent 怎么决定下一步调用哪个 Tool？
9. Evidence 在哪里保存？
10. 服务重启后 Investigation 为什么不会消失？
11. Proposal 为什么不能直接让 Agent 创建正式 Campaign？
12. 哪些安全规则靠 Prompt，哪些必须靠代码？
13. `runtime.py` 和 `main.py` 分别负责什么？
14. 这个项目为什么属于 Agent，而不是固定 Workflow？

如果这些问题都能自己回答，说明对这部分源码已经形成完整理解。

---

# 19. 最终需要掌握的核心架构思想

## 19.1 Agent 不直接掌握业务数据库

```text
Agent
↓
Tool
↓
Java Business API
↓
Business Service
↓
Database
```

---

## 19.2 Tool 参数强类型化

```text
LLM
↓
Pydantic Tool Args
↓
Validation
↓
Business API
```

---

## 19.3 Agent 调查过程是动态的

```text
Evidence A
↓
LLM 判断
↓
Tool B

Evidence B
↓
LLM 再判断
↓
Tool C
```

不是提前写死完整流程。

---

## 19.4 Agent 状态必须持久化

```text
Investigation
Evidence
Hypothesis
Diagnosis
Proposal
↓
Workspace DB
```

而不是只存在模型上下文。

---

## 19.5 LLM 不应该成为最终业务权威

```text
Agent
= 分析 + 建议

Java
= 校验 + 权限 + 最终业务写入
```

最终：

```text
Diagnosis
↓
CampaignProposal
↓
Java Validation
↓
Campaign Draft
↓
Human Confirmation
↓
Campaign
```

这也是 PulseFlow Agent 当前最重要的架构边界。

---

# 20. 推荐实际学习路线

第一次：

```text
domain
→ dependencies
→ pulseflow_api
```

目标：搞懂对象和依赖。

第二次：

```text
growth_investigator
→ observations
```

目标：搞懂 Agent + Tool Calling。

第三次：

```text
workspace
→ migrations
```

目标：搞懂持久化。

第四次：

```text
campaign_proposal
→ Java Draft Boundary
```

目标：搞懂 Agent 与业务系统边界。

第五次：

```text
runtime
→ main
```

目标：把整个运行流程串起来。

最后再结合：

```text
tests/
```

验证不同数据下 Agent 为什么选择不同的 Tool 调用路径。

---

## 一句话总结

PulseFlow Agent 的源码不要按“Python 文件”学，而应该按下面这条主线学习：

```text
业务对象
→ Agent Dependencies
→ Tool
→ Java 数据
→ Evidence
→ 动态决策
→ Diagnosis
→ Proposal
→ Workspace 持久化
→ Java 最终业务执行
```

把这条线真正讲清楚，基本就掌握了整个 `pulseflow-agent` 的核心。
