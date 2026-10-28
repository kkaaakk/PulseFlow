# PulseFlow 全仓库职责归属与信任边界审计

审计日期：2026-10-13（Asia/Shanghai）。本报告仅为审计和重构建议，未实施生产代码修改、重构、提交、合并或 PR。

**结论：发现 40 项已确认问题（P0 15、P1 23、P2 2），另有 2 条有条件的结构建议及 4 项待验证事项。** 问题集中于模型内容检查的实际覆盖、调查运行状态收敛、Java 业务语义落地及异步任务恢复。不能以现有测试通过推断这些路径已经正确，也不需要推翻现有单 Agent、Java 权威写入和双数据库边界。

“已确认”表示已读到可达实现及相关调用/测试，或已经离线复现；**不表示发生过生产事故**。部分确认缺陷的触发前提是存量无 owner 草稿、故障/取消、并发或后续 Campaign 激活。环境暴露程度和存量数据规模仍需另行核验。按同一职责根因合并计数，一项可能包含多个同源失败路径；普通 DTO 和简单委托不机械计为问题。

## A. 当前架构总览与审计基准

### A.1 Git 与覆盖范围

- 分支：`codex/simplify-agent-proposal-boundary`。
- HEAD：`40febc966fca791e67445441a7eaca8cf60b4d10`。
- 基准：**当前磁盘工作树**，不是仅审计 HEAD。开始时已有 290 个修改项、17 个删除项及 4 个未跟踪项（Git 按目录折叠），本轮不覆盖或恢复它们。
- 已对 355 个原有非缓存文件建立 SHA-256 快照，源码核查结束时与起始快照比较为 0 变更；报告保存阶段另建保护快照，完成时复核。
- 已检查根目录及父目录，没有发现适用的 `AGENTS.md`。使用 architecture-designer 技能组织责任分析、失败模式和提案决策记录，未据此增加审批或实施动作。

| 范围 | 有效实现与相关文件 | 核查方式 |
|---|---:|---|
| Python Agent | src 17 个 Python 文件；4 个 Alembic 文件；18 个测试 Python 文件 | AST 类/方法/嵌套 Tool 索引；逐方法阅读；追踪实际 Agent 循环；SQLite、MockTransport、FunctionModel 故障探针 |
| Java | 7 个 Maven 子模块；157 个 main Java 文件；30 个测试 Java 文件；8 个 POM；7 个 Flyway SQL | 阅读全部业务实现和 Mapper SQL；DTO/实体/枚举按类评估；核对 Spring 代理、事务、消息和恢复调用关系 |
| Web | src 37 个文件；3 个单元测试文件、2 个 E2E 文件；Vite/Vitest/Playwright/ESLint 配置 | 阅读全部 API、视图、Store、Router、组件、类型和样式；检查异步状态、真实/演示数据、SSE 和人工确认链路 |
| 工程与部署 | testing 中 13 个 Python/PowerShell/JS 脚本、SQL fixture、Compose、Dockerfile、CI、公开配置样例 | 阅读初始化、迁移、测试所有权、回放、验收器及性能边界；核对实际启动和关闭顺序 |

排除项：`.git`、构建产物 `target/dist`、依赖/缓存、运行时报告和生成数据；`work/` 下本地工作材料；被根 `.gitignore:63` 明确排除、且含独立 `.git` 的 `pii-benchmark/` 实验仓库。未直接查看或输出真实 `.env`、密钥内容，也未访问生产数据库；首次测试加载本地配置导致的污染另见 F35。`pulseflow-ai/target` 的旧测试报告是构建残留，当前 Maven 无该模块/源码，未计入有效代码或本轮测试结果。

已读实际源码而不只统计文件；下文 A.3、F 和附录给出职责/方法/调用关系索引。静态引用搜索仅用于定位和删除候选，不能证明框架入口或外部集成永远没有调用。

### A.2 当前目录与模块职责

~~~text
PulseFlow/
├── pulseflow-agent/
│   ├── src/pulseflow_agent/
│   │   ├── main.py                       FastAPI、lifespan、API、认证、SSE
│   │   ├── config.py                     环境配置和启动配置约束
│   │   ├── runtime.py                    单进程并发/速率准入
│   │   ├── agent/                        模型组装、Tool、Instructions、Observation
│   │   ├── domain/                       调查/方案类型、契约及可变 Workspace
│   │   ├── workspace/                    SQLAlchemy 表、Repository、应用 Service
│   │   ├── clients/                      Python → Java 聚合 Tool HTTP
│   │   ├── security/                     本地业务敏感字段 + Azure PII
│   │   └── observability/                官方 OTel 适配、白名单导出、质量统计
│   ├── migrations/                      Agent 独立 schema 的 Alembic
│   └── tests/                           单元、持久化、轨迹、评测、PII、Trace
├── pulseflow/
│   ├── pulseflow-common/                 持久化实体、Mapper、公共 DTO/枚举/工具
│   ├── pulseflow-profile/                Redis/MySQL 画像读取及八种标签策略
│   ├── pulseflow-campaign/
│   │   └── .../campaign/
│   │       ├── dsl/ validation/          业务 DSL 与字段/规则校验转换
│   │       ├── draft/ preview/          草稿生命周期和聚合人群预估
│   │       ├── decision/ delivery/      决策、任务、频控、渠道模拟发送
│   │       ├── delay/ attribution/      延迟执行、点击、归因与恢复
│   │       ├── analytics/ content/      绩效及部分无运行调用的旧能力
│   │       └── profile/                  实时画像写入（所有权位置不合适）
│   ├── pulseflow-event/                  HTTP → Kafka → 事实/画像/决策
│   ├── pulseflow-job/                    XXL 调度、聚合、标签、执行及补偿
│   ├── pulseflow-simulator/              独立模拟器模块，当前未被 boot 依赖打包
│   └── pulseflow-boot/
│       ├── .../boot/config/             Spring 资源、鉴权、Telemetry、异常
│       ├── .../boot/web/                普通查询、会话、Web DTO
│       ├── .../boot/agenttools/         内部 Tools、调查/SSE 网关、Proposal → Draft
│       └── resources/db/migration/      V1–V7
├── pulseflow-web/src/
│   ├── api/                             Java HTTP、SSE、明确的 demo fixture
│   ├── stores/auth.ts router/           会话和页面准入
│   ├── views/                           各业务页及调查/草稿工作流
│   └── components/ types/ utils/ ...    组件、前端契约和格式化
├── testing/                             系统回放、状态验收、性能专项
└── .github/workflows/ci.yml              离线 Agent、Java、前端回归
~~~

Maven POM 的真实依赖方向（箭头表示依赖）：

~~~mermaid
graph TD
  Boot["boot"] --> Event["event"]
  Boot --> Campaign["campaign"]
  Boot --> Profile["profile"]
  Boot --> Job["job"]
  Boot --> Common["common"]
  Event --> Campaign
  Event --> Profile
  Event --> Common
  Campaign --> Profile
  Campaign --> Common
  Profile --> Common
  Job --> Event
  Job --> Campaign
  Job --> Profile
  Job --> Common
  Simulator["simulator"] --> Event
  Simulator --> Common
~~~

当前 Maven 模块图无环。`common` 的 MyBatis 注解实体是持久化记录，不是声称独立于框架的富领域模型；不能仅因注解存在就要求重建一套 Domain Entity。`boot` 扫描了 simulator 包名，但 POM 不依赖 simulator；**包扫描字符串不等于该 Controller 已在默认部署运行**。

Python 当前主要依赖：`main → Service/Agent/Repository/Security`，`Service → Agent + Repository + Workspace`，`Agent → Client + Domain + Security`，`Repository → Domain + models`。`domain/investigation.py` 对 Repository 是 `TYPE_CHECKING` 类型依赖，不能误报为已存在的运行时 import 环；但可变 Workspace 的层次位置仍有问题。

### A.3 关键真实调用链

**Python 调查：**

~~~text
Java /start 或 /resume
 → main.internal_auth（机器认证）
 → InvestigationService.start
 → RunAdmission.acquire
 → 问题/范围 PII 检查
 → Repository.create 或 begin_followup（状态 CAS）
 → Workspace 构造及可选 change_scope
 → 后台 task → Service._run（总体 timeout）
 → GrowthInvestigator.run
 → Prompt 检查、USER 消息、AgentDependencies
 → Pydantic AI Agent.run（官方 UsageLimits、动态 Tools、Schema/Retry）
 → instructions: Workspace.context_text
 → 具体 Tool → Client → Java 聚合服务
 → Observation → guardrail → Workspace.add_evidence → Repository 事务
 → Hypothesis / 范围 / Proposal 工具按模型选择执行
 → output_validator 校验证据引用
 → 最终 Diagnosis PII 检查
 → Workspace.finish → Repository.finish（状态 + 助手消息同事务）
 → Java publicView → Web GET 快照展示
~~~

同步 `create/follow_up/propose` 也走 `_run`，但只有 `start` 创建的后台任务进入 `_tasks`；这不是同一取消/关闭保证，见 F03。模型决定工具路径；测试中的脚本策略不参与生产决策。

六类 Java 只读工具为 `query_metric`、`compare_metric`、`breakdown_metric`、`get_campaign_performance`、`get_attribution_breakdown`、`preview_audience`；另有五个工作区/方案工具。工具正常失败变成固定 Observation 错误码，不能产生 Evidence。PII/存储错误不应被当成普通可用性 Observation 吞掉。

**Java 与 Agent、Proposal 与 Draft：**

~~~text
浏览器会话
 → SaToken / Controller 取得登录 operator
 → Java InvestigationOwnership（资源归属）
 → Java AgentProposalGateway → Python 内部 API
 → Python 仅访问 Java 内部聚合 Tool Controller/Service
 → Python 保存 CampaignProposal（owner + scope + supporting Evidence）
 → 用户单独点击“生成 Draft”
 → CampaignProposalDraftController
 → CampaignProposalDraftService.create（Java 事务）
   → 先读已有 Draft 幂等返回；否则 AgentProposalClient.read
   → owner 行锁、Proposal owner/status/引用验证
   → Java DSL Validator + AudiencePreview
   → CampaignDraftService.createDraft + investigation_id
 → Java 事务提交
 → Controller 尝试 markDraftCreated（失败不撤销已提交 Draft）
 → 用户人工确认
 → CampaignDraftService.confirmAndCreate
 → Java Campaign + CampaignRule，Campaign 状态 DRAFT
~~~

现有 Proposal → Draft 主方向正确：Python 不能确认、激活或发送 Campaign，Java 不接受浏览器在 Draft 生成请求中替换 owner 或优惠事实。已提交 Draft 优先返回和随后修复关联的语义必须保留；问题是编排归属、可恢复性和部分具体不变量。

**业务事实链：** `EventController → EventService → Kafka → EventPersistenceService → RealtimeProfileUpdateService → DecisionEngine → DeliveryService → DeliveryConsumer/FrequencyControl → ClickEvent/Attribution → PerformanceSummary`。CLICK 物化入口目前断开，归因/恢复还存在失败窗口，不能把 fixture 中预写的 Click 当作真实闭环证据。

**SSE：** Python 轮询持久化快照，仅输出白名单事件及 ID/工具名/状态；Java 校验 owner 后转发过滤帧；Web SSE 触发合并 GET 刷新。SSE 不是原始 LLM token 流，不持久化推理过程，不是最终状态权威。终态读取仍以数据库快照为准。

### A.4 已执行验证及限制

| 验证 | 本轮结果 | 能证明的范围 |
|---|---|---|
| Python 原始 pytest | 9 失败、38 通过、2 跳过 | 暴露本地 .env 污染离线测试；失败不是九个生产功能缺陷 |
| Python 隔离配置 pytest | **47 通过、2 跳过** | test/FunctionModel、MockTransport、SQLite、22 个离线评测场景等现有断言 |
| Ruff / mypy | 通过；mypy 35 个源文件无问题 | 静态规则和类型检查 |
| Java `mvn -B -q -o clean test` | **123 通过，26 个当前 Test 类，0 失败/错误/跳过** | 清理产物后基于当前源码的单元测试；不含 4 个 IT 源文件中的 Docker 集成测试 |
| Web Vitest | **3 文件、7 测试通过** | 现有响应解析、工具函数、调查组件断言 |
| Web typecheck / lint | 通过 | 类型和 ESLint；未执行 build 或浏览器 E2E |
| testing 纯本地测试 | **9 通过、4 个 subtest 通过** | 测试所有权和 Nullable/CLI 解析，未实际清理或回放外部存储 |
| 专项探针 | Python 6 类边界、Java DSL 4 个输出、Validator 无证据 PASS | 见 G.2；均使用合成输入和离线/本地执行，无真实 LLM 请求 |

前端测试首次因沙箱限制 esbuild 子进程出现 EPERM，获得工具自动批准的同命令执行后通过；这不是前端架构缺陷。首次 Python 测试部分用例误入真实 Azure 分支，之后显式隔离模型环境；后续探针仅使用假配置/MockTransport。未启用真实模型评测。

未执行：真实 MySQL/Kafka/Redis 故障注入、Docker Compose smoke、真实 Java ↔ Python ↔ 浏览器 Proposal 全链路、付费模型/PII 质量评测、k6。上述相关实现与测试已阅读，但**未把未运行或历史报告算作通过**。当前环境/权限并非源码缺陷的替代证据。

## B. 职责归属问题清单

位置缩写：`P=pulseflow-agent/src/pulseflow_agent`，`PT=pulseflow-agent/tests`，`JB=pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot`，`JC=pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign`，`JE=.../pulseflow-event/.../event`，`JJ=.../pulseflow-job/.../job`，`JP=.../pulseflow-profile/.../profile`，`JCO=pulseflow/pulseflow-common/src/main/java/com/pulseflow`，`W=pulseflow-web/src`。文件链接均展开为本工作区绝对路径；行号基于审计快照，通常指方法入口或关键分支。

### B.1 Python Agent

| 优先级 | 文件 | 类/方法 | 当前职责 | 存在的问题 | 建议归属 | 调整理由 |
|---|---|---|---|---|---|---|
| P0 · F01 · 已确认 | [P/agent/growth_investigator.py:157](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:157)；[P/security/pii_guardrail.py:68](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/security/pii_guardrail.py:68) | _register_tools / run / check | 在业务回调检查 Prompt、观察和最终输出 | Schema 拒绝早于 Tool 函数；探针确认原始禁字段经 RetryPromptPart 进入第 2 次模型请求。instructions 检查不覆盖全部消息历史。 | Security 的官方模型请求/响应钩子；Agent 注册 | 保护实际跨出的内容；复用 Pydantic AI Hooks，不重建 Agent 循环。 |
| P0 · F02 · 已确认 | [P/domain/campaign_proposal.py:41](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/campaign_proposal.py:41)；[P/agent/growth_investigator.py:263](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:263)；[P/agent/growth_investigator.py:381](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:381) | free_text / preview_audience / persist_campaign_proposal | 手工投影字符串做 PII 检查 | 投影遗漏 send_at/timezone 等任意字符串；探针局部通过而完整模型被阻断。完整 DSL 的 Java 出站也缺输入检查，原始 Proposal 会保存/返回。 | Security 内容检查；Tool 在出站/写入边界调用 | PII 负责内容，日期/时区/优惠业务规则仍交 Schema 和 Java；两类检查不能相互替代。 |
| P0 · F03 · 已确认 | [P/workspace/service.py:59](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:59)；[P/agent/growth_investigator.py:425](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:425)；[P/agent/growth_investigator.py:511](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:511) | start / _run / cancel / run | Service 管名额，Agent 管部分终态 | scope 初始化失败、最终 guardrail 阶段取消都可留下 DB=RUNNING、active=0、tasks=0；同步调用不登记可取消任务。 | InvestigationService 统一持久化运行生命周期 | 从取得 ID 起覆盖初始化、执行、检查、finish 的异常/取消；同步和后台采用相同终态保证。 |
| P0 · F04 · 已确认 | [P/agent/growth_investigator.py:175](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:175)；[P/agent/growth_investigator.py:339](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:339)；[P/domain/investigation.py:165](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:165) | query_metric / update_scope / add_evidence | 并行 Tool 共享可变 Workspace | 查询从范围 0 开始，切范围后证据被标为范围 1，已离线复现；其他 Workspace 写也缺完整串行约束。 | Tool 的 sequential 设置 + Workspace 范围版本契约 | 使用框架顺序控制隔离变更，查询捕获起始版本并在写入核验；无须关闭所有只读并发。 |
| P0 · F05 · 已确认 | [P/agent/growth_investigator.py:388](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:388)；[P/domain/investigation.py:70](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:70) | validate_diagnosis / Finding | 检查 Evidence 子集 | 顶层放真实 ID，Finding.evidence_ids=[] 仍可通过 DIAGNOSED；违反逐条事实引用约束，已复现。 | Domain 纯引用规则；Agent 转 ModelRetry | 每个事实 Finding 要有当前合法引用；引用合法仍不等于证明自然语言因果真实性。 |
| P1 · F06 · 已确认 | [P/workspace/service.py:33](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:33)；[P/main.py:193](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:193)；[P/main.py:382](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:382)；[P/main.py:395](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:395) | Service.__init__ / ready / get_proposal / mark_proposal_draft | 私有设置装配准入、路由穿透仓储 | 依赖 investigator._settings 和 service._repository；策略和实现细节绕过明确应用入口。 | Bootstrap 注入 RunAdmission；Service 公开就绪/Proposal 操作 | 少量有边界价值的委托应保留；不新增无使用价值的接口链。 |
| P1 · F07 · 已确认 | [P/domain/investigation.py:122](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:122) | InvestigationWorkspace 全部可变方法、context_text、add_evidence | 领域不变量、仓储、Prompt、Trace | 实际是每轮应用会话，却位于 domain；同时依赖 Repository 类型、OTel 和上下文格式。 | workspace/session.py；渲染 → agent/instructions.py；Trace → Tool 适配 | 移动整个内聚会话，不把每个操作拆成 Service；保留 Domain DTO/纯不变量。 |
| P1 · F08 · 有条件建议 | [P/agent/growth_investigator.py:100](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:100) | _register_tools / _save / run | 模型组装、11 个 Tool、状态持久化及统计 | 三个变化方向集中闭包。不是因 548 行必须拆；在一次修复 F01–F07 时提取协作模块才有明确收益。 | Agent 组装/调用；agent/tools.py 注册；Service 终态；Domain 纯规则 | 保留动态调用与原签名/Schema；禁止一工具一文件、抽象工厂和自制执行框架。 |
| P1 · F09 · 已确认 | [P/main.py:75](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:75)；[P/agent/growth_investigator.py:56](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:56) | lifespan / create_model / create_app | 装配、API、资源关闭 | OpenAIProvider 自建 HTTP 客户端却未进入框架 Agent async context；关闭链未覆盖该资源。engine 建好后 instrument 又在外层 finally 之前。 | bootstrap.py 组织 AsyncExitStack；Agent 公开生命周期并委托框架 | 复用框架清理；底层初始化仍在各组件，Bootstrap 只组织与逆序释放。 |
| P1 · F10 · 已确认 | [P/workspace/service.py:49](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:49)；[P/workspace/service.py:53](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:53)；[P/workspace/service.py:212](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:212)；[P/observability/tracing.py:37](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/observability/tracing.py:37) | _run / start / propose / release / record_status | 超时、PII 前检、状态统计分散 | 部分外部 PII 检查在总体 timeout 外；入口拒绝绕过质量统计；日志 DIAGNOSED/FAILED 与 DB COMPLETED/CANCELLED 不一致。 | Service 的运行控制/状态映射；Observability 接收统一结果 | 区分运行状态和诊断质量；覆盖请求执行预算，保留不同边界检查，不能仅合并所有 check。 |
| P1 · F11 · 已确认 | [P/workspace/repository.py:109](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:109) | check_ready | 查两个表的 id | 缺 Evidence/Hypothesis/Message 表或 Proposal 新列仍可能 ready，直到业务查询才失败；不能证明迁移到 head。 | Repository 的存储检查 | 检查受支持版本及必要表列；生产不能用自动 create_all 替代迁移，也不必每次付费模型联通检查。 |
| P1 · F12 · 已确认 | [P/clients/pulseflow_api.py:112](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/clients/pulseflow_api.py:112)；[P/clients/pulseflow_api.py:125](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/clients/pulseflow_api.py:125)；[P/clients/pulseflow_api.py:164](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/clients/pulseflow_api.py:164) | 四种查询响应契约 | 校验 metric、顶层维度和行数 | 未核对响应时间窗/比较两期与请求，也未核对行维度键；可能在原条件下保存另一窗口事实。 | Client 的语义响应验证 | 属跨服务契约完整性，不应搬入通用 Security；extra=forbid 不能覆盖语义对应。 |

### B.2 Java 业务一致性与最终写入

| 优先级 | 文件 | 类/方法 | 当前职责 | 存在的问题 | 建议归属 | 调整理由 |
|---|---|---|---|---|---|---|
| P0 · F13 · 已确认 | [JC/draft/CampaignDraftService.java:117](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:117)；[JC/draft/CampaignDraftService.java:203](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:203) | updateDraft / refreshPreview / confirmAndCreate | 读实体、改状态、事务内建活动 | 确认无行锁/CAS，可并发创建两个 Campaign；编辑/刷新可覆盖终态。确认先写 INVALID/EXPIRED 再抛异常，该标记随事务回滚。 | DraftService + Mapper 的锁定/条件更新 | 读—授权—迁移—写入同一事务边界；错误状态采用明确提交策略，不能为了记失败误提交活动。 |
| P0 · F14 · 已确认 | [JC/draft/CampaignDraftService.java:303](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:303)；[pulseflow/pulseflow-boot/src/main/resources/db/migration/V3__ai_campaign_tables.sql:13](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V3__ai_campaign_tables.sql:13) | requireDraftOwner | 历史 owner=null 兼容放行 | 任意已登录账号可读/改/确认无归属历史 Draft；NULL 允许且无隔离迁移。代码路径确认，存量规模待 V03 核查。 | Java 草稿业务授权 | 历史只读隔离或显式管理员认领；兼容性不能成为业务写授权。 |
| P0 · F15 · 已确认 | [JC/validation/CampaignDslValidator.java:50](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignDslValidator.java:50)；[JC/validation/DslToRuleConverter.java:49](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/DslToRuleConverter.java:49)；[JC/validation/DslToRuleConverter.java:97](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/DslToRuleConverter.java:97)；[JC/decision/DecisionEngine.java:237](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/decision/DecisionEngine.java:237) | validateAudience/Value、convert、toThreshold、allRulesMatched | DSL → 执行规则 | 预估 OR、执行 AND；9.9 → 9；INTEGER 的 Number 1.5 可通过。Java 探针复现三种不一致。 | Campaign 可执行 DSL 契约 | 优先拒绝当前引擎无法表达的语义并保留精度；不能静默近似或交 Python 兜底。 |
| P0 · F16 · 已确认 | [JC/validation/CampaignDslValidator.java:329](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignDslValidator.java:329)；[JC/draft/CampaignDraftService.java:342](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:342)；[JC/draft/CampaignDraftService.java:351](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:351) | validateFrequencyCap / pickDailyLimit / pickWeeklyLimit | 任意小时窗口 → 日/周计数器 | 每 720h 最多 1 次等配置变成默认日 3/周 1，原窗口保证丢失；UI 仍展示原承诺。 | Campaign 频控映射 + Validator | 现有表结构下只接受可兑现窗口；保留发送时 Redis 原子预留，不能只改文案。 |
| P0 · F17 · 已确认 | [JC/validation/CampaignDslValidator.java:119](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignDslValidator.java:119)；[JC/dsl/PromotionFact.java:24](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/PromotionFact.java:24)；[JC/draft/CampaignDraftService.java:373](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:373) | validate / PromotionFact / buildMessageTemplate | 非空事实作为 VALIDATED 条件 | 未校验类型、范围、金额关系、有效期/null 元素；负 discount 仍 valid。普通浏览器 Draft CRUD 可绕过 Python 约束。 | Java 优惠业务校验 | Java 独立维护最终业务边界；保留 Python exact authorized facts，不可互相删除。 |
| P0 · F18 · 已确认 | [JE/service/EventPersistenceService.java:61](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java:61)；[JE/service/EventPersistenceService.java:81](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java:81) | persist | 事件 + 小时桶同事务 | insert 后指标失败被 catch 并 return fail，事务代理正常提交；重投 duplicate 分支跳过指标，可能永久缺桶。 | 事务服务的异常契约；Consumer 处理失败 | 非重复失败越过事务边界抛出或 rollback-only；重复键只在窄分支处理。 |
| P0 · F19 · 已确认 | [JC/decision/DecisionEngine.java:255](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/decision/DecisionEngine.java:255)；[JC/decision/DecisionEngine.java:295](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/decision/DecisionEngine.java:295) | evaluateRule / evaluate / evaluateBatch | 规则匹配及异常分类 | 内层吞 Redis/DB 异常变 false，使补偿/Job 当成功；未知规则类型又 true，可扩大触达。 | 业务规则评估的明确结果/异常 | 非法规则 fail-closed；临时基础设施失败传播给既有补偿。不能混为“规则不匹配”。 |
| P0 · F20 · 已确认 | [JE/consumer/EventConsumer.java:97](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java:97)；[JJ/handler/CompensationJob.java:81](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CompensationJob.java:81)；[JC/attribution/AttributionService.java:127](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java:127) | consume / 重放 / 归因领取恢复 | 另建归因、pending→processing | Phase 2 失败不建归因，EVENT_REPLAY 不补建；归因 insert 失败仅日志后 ACK。归因 processing 无崩溃超时回收，ensure_pending 永久跳过已有 processing；通用恢复扫另一套键。 | 事件应用流程的完整重放；归因队列自身租约恢复 | 补耐久建任务/超时恢复；保留 after-commit 和目标事件唯一约束，不强制合并所有队列。 |
| P0 · F21 · 已确认 | [JJ/handler/RetryCompensationJob.java:49](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/RetryCompensationJob.java:49) | PROCESSING 超时恢复分支 | 改 WAIT_RETRY、加次数 | 没设置 next_retry_at；初次任务该列 NULL，下一阶段 le(next_retry_at, now) 不会选中。 | Delivery 恢复服务；Job 委托 | 一次合法迁移同时写重试时间、清 processing 标记，覆盖首次领取后直接崩溃。 |
| P0 · F22 · 已确认 | [JJ/handler/CampaignSelectionJob.java:102](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignSelectionJob.java:102)；[JJ/handler/CampaignSelectionJob.java:145](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignSelectionJob.java:145) | createExecutionInstance / computeNextTriggerAt / executeCampaign | Job 管调度 CAS、实例和重试 | 推进 next_trigger_at 自动提交后才 insert，失败丢本轮；无 cron 默认 +1h 违背 ONCE；立即失败回 PENDING 不统一受重试上限限制。当前新 Campaign 为 DRAFT，风险在激活/调度后触发。 | Campaign 事务化调度应用服务 | 推进与实例同事务；ONCE 不生成下一轮；保留 execution+user 幂等键，明确重试上限。 |

### B.3 跨服务、查询、前端与工程职责

| 优先级 | 文件 | 类/方法 | 当前职责 | 存在的问题 | 建议归属 | 调整理由 |
|---|---|---|---|---|---|---|
| P1 · F23 · 已确认 | [JB/agenttools/AgentProposalGateway.java:62](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:62) | investigate / Ownership.register | Python start 后 Java 登记 owner | 登记失败或响应丢失时调查继续耗资源但无 Java 入口，重试生成新调查。授权仍 fail-closed，问题是部分成功和恢复。 | 调查应用编排 + Ownership | 明确补偿取消/审计；完整去重若需请求身份，显式协议调整，不能以跨服务 @Transactional 假装原子。 |
| P1 · F24 · 已确认 | [JB/agenttools/AgentProposalGateway.java:31](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:31) | CRUD、call、events、publicView、shutdown | Controller + HTTP + SSE + 投影 + 线程生命周期 | 多个变化方向互相牵动；readLine 完成后才检查长度，断连仅设标志，阻塞读不能及时释放线程。 | Controller + AgentInvestigationClient + InvestigationEventRelay | 三个真实职责，不造通用网关框架；有界读取/主动关闭上游，保留会话与归属检查。 |
| P1 · F25 · 已确认 | [JB/agenttools/CampaignProposalDraftController.java:27](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftController.java:27)；[JB/agenttools/CampaignProposalDraftService.java:44](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftService.java:44) | create / 回写协调 | Controller 提交后回写；事务内 HTTP 取快照 | 提交后修复语义正确，但编排属于应用层；静默失败只能等再次 POST；网络/预估扩大事务占用。RR 普通重查可见性见 V02。 | ProposalDraftApplicationService + 独立事务 DraftService | 事务外取不可信快照，Java 事务内授权/幂等/写入，提交后回写并记录安全指标；不能将回写塞入写事务。 |
| P1 · F26 · 有条件建议 | [JB/agenttools/AgentMetricService.java:44](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:44) | query/compare/attribution/readCounts/SQL helpers | 约束 + 固定 SQL + 计算 + DTO | 固定白名单/绑定参数本身合理，不能凭方法多判错。SQL 模式或测试持续增多时，事实读取提取才有收益。 | 同 feature 的 AgentMetricReadRepository + 原 Service | SQL 读细节提取，业务参数约束/数学留 Service；不引入接口，不让 campaign 反向依赖 boot DTO。 |
| P1 · F27 · 已确认 | [JB/web/WebQueryService.java:63](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:63) | 各查询、映射、画像回退 | 13 个依赖覆盖多个读模型 | 独立功能互相牵动；mapping 内查询形成 N+1；MySQL 画像回退复制最新快照逻辑；测试须构造全套依赖。 | Campaign/User/Activity/Dashboard 四组 QueryService | 按能力分组并批量查询，映射留组内；不是每条 SQL 一个 Service，也不统一成万能 QueryManager。 |
| P1 · F28 · 已确认 | [JB/web/WebQueryService.java:521](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:521)；[W/views/Dashboard.vue:93](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Dashboard.vue:93)；[W/views/Dashboard.vue:109](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Dashboard.vue:109) | performanceNumbers / trends / rate 展示 | 各入口自行计算指标 | 有摘要用去重点击/转化和 converted/clicked；回退用次数和 converted/sent。conversions 趋势返回次数却按百分比画；列表 CTR 用 clicked/sent。 | Java 统一带口径分析结果；前端格式化 | 共享业务定义和事实聚合；只共享 rate 函数不等于口径一致。 |
| P1 · F29 · 已确认 | [JB/web/WebQueryService.java:625](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:625)；[JB/web/WebQueryService.java:659](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:659)；[W/views/Dashboard.vue:24](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Dashboard.vue:24)；[W/layouts/AppShell.vue:66](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/layouts/AppShell.vue:66) | count/queryBuckets/kafkaStatus/kpis | 查询降级及展示事实 | DB 异常成真实 0；Kafka 恒 UP；真实模式固定昨日增长、告警和“基础设施正常”。 | 可用性/来源读模型；Actuator；demo fixture | 未知不能冒充 0；无来源值隐藏/空态，不要求新造一套观测系统。 |
| P1 · F30 · 已确认 | [JJ/handler/WindowMetricJob.java:99](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/WindowMetricJob.java:99)；[JJ/handler/TagRecalcJob.java:37](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/TagRecalcJob.java:37)；[JP/tag/strategy/PriceSensitiveTagStrategy.java:18](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/PriceSensitiveTagStrategy.java:18)；[JC/validation/CampaignFieldRegistry.java:33](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignFieldRegistry.java:33) | 画像生成、标签、字段注册 | 分别定义字段语义 | active_7d 为浏览次数却作 <=7 天；PRICE_SEN 与 PRICE_SENSITIVE 不符；实时字段只读窗口、部分字段无生成器；长期不活跃者不再重算标签。 | Profile 指标/标签契约与计算；Campaign 只注册可执行能力 | 同步生产者、读取、规则适配；禁用未兑现字段或补实现，不能靠 seed 掩盖。 |
| P1 · F31 · 已确认 | [JC/preview/SqlAudiencePreviewService.java:79](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/SqlAudiencePreviewService.java:79)；[JC/preview/SqlAudiencePreviewService.java:150](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/SqlAudiencePreviewService.java:150)；[JB/web/WebQueryService.java:512](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:512) | preview/evalOne/activeTags | 标签/指标圈人及展示 | 先筛 value=1 再“最新”，旧1新0仍命中；false 标签也算 true；50k 截断无警告；缺指标与执行端按0语义不同；Web 同样忽略新0。 | Profile 最新标签读取 + Campaign 人群求值 | 先取最新再解释值；明确否定/缺失规则和截断，不伪称全量人数。 |
| P1 · F32 · 已确认 | [JC/analytics/PerformanceSummaryCalculator.java:66](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java:66)；[JJ/handler/CampaignPerformanceSummaryJob.java:29](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignPerformanceSummaryJob.java:29) | compute / execute | 首次计算后返回已存摘要 | 可能早于归因宽限/发送重试，晚到事实永不刷新；无 end_time 新 DSL Campaign 不入 Job。 | 分析服务刷新/封存策略；Job 调度 | 完整窗口前可幂等重算，之后封存；GET Tool 必须继续只读。 |
| P1 · F33 · 已确认 | [pulseflow/pulseflow-boot/src/main/resources/application.yml:99](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/application.yml:99)；[JC/draft/CampaignDraftService.java:174](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:174)；[JC/preview/SqlAudiencePreviewService.java:98](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/SqlAudiencePreviewService.java:98) | Mapper 日志/Preview 异常/普通 HTTP 错误 | Trace 过滤之外记录/保存原始异常 | com.pulseflow=DEBUG 包含 Mapper 参数；preview failed + getMessage 可保存并返回 Draft API；普通异常部分回显原文。 | 日志策略 + 固定 API 失败投影 | 保留安全 exporter，另补普通日志/HTTP 边界；操作码和 trace ID 替代原始 SQL/业务参数。 |
| P2 · F34 · 已确认 | [JC/analytics/AudienceMetricsAggregator.java:42](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/AudienceMetricsAggregator.java:42)；[JC/preview/SqlAudiencePreviewService.java:119](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/SqlAudiencePreviewService.java:119)；[JC/content/CampaignContentValidator.java:66](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/content/CampaignContentValidator.java:66) | 旧聚合/内容能力、previewUserIds、无调用 DTO | 无运行调用或占位实现 | Aggregator 只有自身调用；previewUserIds 仅声明/实现且恒空；ContentValidator 仅自身/测试。DecisionResult、FrequencyCheckResult、DevLoginResponse 仅声明。 | 删除候选或明确非运行库 | 调用证据见 F.4。不能把 ClickEventService 同样直接删掉，也不能因无 Java 调用删除历史迁移/表。 |
| P1 · F35 · 已确认 | [PT/conftest.py:10](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/conftest.py:10)；[PT/agent/test_safety.py:20](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/agent/test_safety.py:20)；[PT/proposals/test_proposal.py:46](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/proposals/test_proposal.py:46)；[.github/workflows/ci.yml:60](C:/workbuddy-workspace/PulseFlow/.github/workflows/ci.yml:60) | 离线 Settings fixture | 禁真实模型，部分设置却读 .env | 本轮 9 个失败来自进入真实 Azure 分支；CI 强设 test 掩盖本地隔离缺口；ALLOW_MODEL_REQUESTS 不约束 Azure HTTP。 | 测试配置和网络隔离 | 显式 test、_env_file=None、假密钥/MockTransport；真实评测继续 opt-in，生产 fail-closed 不变。 |
| P2 · F36 · 已确认 | [P/agent/dependencies.py:14](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/dependencies.py:14)；[P/agent/dependencies.py:32](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/dependencies.py:32)；[P/config.py:32](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/config.py:32)；[P/domain/campaign_proposal.py:53](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/campaign_proposal.py:53) | OperatorContext/operator_context/max_cost/to_dsl | 携带未消费上下文、配置、转换 | operator_context 仅传递无读取；to_dsl 无调用；max_cost_usd 不决策。文档已明示费用不强制，**不计为硬预算漏洞**。 | 删除闲置上下文/转换候选；费用字段保留 reserved 或移除 | 不为预留字段新造身份/成本引擎；有实际用途的 ProposalContext/client/workspace/guardrail 保留。 |
| P1 · F37 · 已确认 | [JC/profile/RealtimeProfileUpdateService.java:32](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java:32) | update / Lua / 时间助手 | Campaign 包内画像写入 | 实现只依赖 Redis、公共 JSON 和事件上下文，不依赖 Campaign；被 Event/Job 复用，变化原因属于 Profile。 | pulseflow-profile/service | 原样迁移、更新引用和测试，仍共享一份 Lua；Profile 不依赖 Event/Campaign，保持无环。 |
| P1 · F38 · 已确认 | [W/views/Investigations.vue:89](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Investigations.vue:89)；[W/views/Investigations.vue:117](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Investigations.vue:117)；[W/views/CampaignDetail.vue:32](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/CampaignDetail.vue:32)；[W/views/UserDetail.vue:34](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/UserDetail.vue:34) | submit/cancel/propose/Draft 操作、详情 load | 路由、任务、SSE、Draft 状态同视图 | generation 只覆盖 load/refresh，其余 await 可写新路由；详情参数变化仅 onMounted 不重载；列表/抽屉也可旧结果覆盖新选择。 | useInvestigationSession / useDraftReview；视图留 UI | 请求身份/取消/过期结果统一维护；保留 API 模块和人工二次确认，不先造全局状态机。 |
| P1 · F39 · 已确认 | [JE/consumer/EventConsumer.java:83](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java:83)；[JC/attribution/ClickEventService.java:24](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java:24)；[testing/functional/campaign-fixture.sql:49](C:/workbuddy-workspace/PulseFlow/testing/functional/campaign-fixture.sql:49) | consume / recordClick | CLICK 只写 user_event | 指标/归因读 click_event，却无生产 HTTP/Kafka 物化调用；fixture 明确预写 click_event 绕过断链。 | 事件应用编排 + ClickEventService | 接通可信任务/用户校验和幂等 canonical CLICK；若新增唯一 source_event_id，单独增量迁移，不删除服务。 |
| P1 · F40 · 已确认 | [JC/attribution/AttributionService.java:311](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java:311) | executeAttribution | 取最新点击再验 Delivery | 最新点击无有效 task/delivery 即 EXPIRED，不检查更早但有效的窗口内触点。 | AttributionService 候选选择规则 | 倒序找首个有效触点，保留事务和目标事件去重，不隐藏进 HTTP/SQL 客户端。 |
| P1 · F41 · 已确认 | [testing/functional/validate.py:1384](C:/workbuddy-workspace/PulseFlow/testing/functional/validate.py:1384)；[testing/functional/validate.py:1548](C:/workbuddy-workspace/PulseFlow/testing/functional/validate.py:1548)；[testing/functional/validate.py:1610](C:/workbuddy-workspace/PulseFlow/testing/functional/validate.py:1610) | write_summary / main http-only | 验收证据汇总 | 无 run_dir/缺 Replay 报告返回 None，checks=[]/failures=[] 却 PASS；本地探针 exit=0、checks=0。 | 验收器的证据准入 | 缺预期执行证据必须 NOT_RUN/FAIL；“没有断言失败”不是“业务已验证”。 |
| P1 · F42 · 已确认 | [JC/profile/RealtimeProfileUpdateService.java:65](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java:65)；[JC/profile/RealtimeProfileUpdateService.java:80](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java:80)；[testing/functional/validate.py:1302](C:/workbuddy-workspace/PulseFlow/testing/functional/validate.py:1302) | last_* 更新 / dailyKey 生命周期 | 到达顺序覆盖时间，每日建 Hash | 合法乱序旧时间覆盖新 last_*；日键无 TTL/删除路径，持续积累；Validator 只验字段存在。 | Profile 时间/保留策略，原共享 Lua | 幂等 Lua 内维护最大事件时间和日键保留期，覆盖回放窗口；不是 Security 职责。 |

B 表共 42 条记录：40 条已确认、2 条有条件结构建议。额外的部署、隔离级别、历史方案策略与真实质量验证列入 G.4，不混入已确认数量。

## C. 安全边界专项报告

### C.1 责任分类与必须保留的调用边界

| 类型 | 策略/规则的归属 | 实际执行边界 | 保留或修复要求 |
|---|---|---|---|
| Authentication：机器认证 | Python security/auth；Java AgentToolAuthFilter/配置 | Python /internal/v1/*，Java /internal/v1/agent-tools/* | 缺配置/缺令牌/错误令牌拒绝；认证先于业务执行。internal_auth 可迁移，注册仍由 API/Bootstrap 完成。 |
| Authentication：操作员会话 | Java Sa-Token 和本地身份目录/未来外部身份源 | Java 普通 /api/* 与 Controller 取得 operator | 浏览器只持会话 token；不向浏览器下发机器凭证；不能信请求中的 owner/operator。 |
| Authorization：资源归属 | AgentInvestigationOwnership、CampaignDraftService、Proposal → Draft 应用服务 | 每次调查读/追问/取消/方案/SSE、每次 Draft 读写和确认 | Java 是最终权威；不能因为 Python 验证过而删除。保持管理员也不能自动读别人的调查这一现有语义；修复 owner=null 的放行。 |
| PII Guardrail | AzurePiiGuardrail 的字段与内容策略 | 初次用户问题、范围/优惠事实、每次真实模型出站、Tool 对 Java 出站、Observation/假设返回、Proposal/Diagnosis 持久化前 | 策略集中、调用点留在信任边界；补 Schema Retry/原始模型内容及完整 Proposal，拒绝时不回显正文。 |
| Business Validation | Python Domain 的引用/假设约束；Java Campaign Validator/规则/频控/归因 | 不可信模型输出转领域对象；Proposal → Draft；Draft 编辑及确认；发送前频控 | 逐条 Evidence、当前 scope、授权事实、可执行 DSL、频控窗口、优惠合法性分别验证；不整体搬入 Security。 |
| Schema Validation | Pydantic 与 Java DTO/Jackson/Bean Validation | HTTP/Tool 参数解析、客户端响应解析 | extra=forbid、枚举、长度、类型、日期必须保留；结构通过不能证明事实正确或权限成立。原始 Schema 错误回模型前仍须内容保护。 |
| Runtime Protection | RunAdmission、Service timeout、Java GatewayLimit、SSE 连接/线程容量 | 请求准入、运行期限、模型/Tool 用量和流式连接 | 与内容安全、授权独立。继续使用官方 UsageLimits；统一终态和清理；单进程限制不能宣传为集群限制。 |
| 日志/Trace/SSE 防泄漏 | Observability 的字段/内容投影；API 的公开响应契约 | 导出器、Logger、HTTP 错误、Python SSE、Java SSE/公开快照 | 保留禁内容 instrumentation + 白名单 exporter；同时修复普通日志/Preview warning。白名单键之外还要限定事件、状态、工具名、ID 格式/长度。 |

认证、授权与 PII 不能相互代替：内部令牌只证明调用服务可信，不证明它可以替某操作员使用某资源；operator_id 也不应为了形式进入 LLM。Python 的 ProposalContext 表示 Java 已认证的上下文，不承担 Java 资源授权的最终责任。

### C.2 重点安全方法逐项归属

- **internal_auth**（[P/main.py:163](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:163)）：比较机器凭证是认证策略，适合迁至 `security/auth.py`；API 只注册 middleware。保留恒定时间比较、长度限制、固定错误和 fail-closed；不让 auth 模块依赖 Repository/Agent/HTTP 业务路由。
- **AzurePiiGuardrail.check / _texts / _check_azure**（[P/security/pii_guardrail.py:38](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/security/pii_guardrail.py:38)）：当前本地先检查、真实模型再 Azure、关闭服务端输入日志、异常统一拒绝的方向正确。不能为了抽象把 Azure 请求放进 bootstrap。PII Provider 属明确的数据处理边界，不能把日志 opt-out 当作完成全部模型隐私保护。
- **模型请求保护**：在当前安装的 Pydantic AI `1.107.6` 源码中已核对 `Hooks.before_model_request` 及 `wrap_model_request` 扩展点。由安全适配器检查实际消息内容、RetryPromptPart 和模型响应内容；利用 framework hook 的运行上下文，不手写循环、重试队列、Token 计数器。具体 hook 注册/返回类型实施时必须与 lock 对齐。
- **模型钩子的数据形状**：已核对 ModelRequest、ModelResponse、RetryPromptPart 是框架 dataclass，当前 `_texts` 只识别 BaseModel/Mapping/列表/字符串。不能直接把消息对象列表交给 `check` 而造成空扫描放行；使用官方 `ModelMessagesTypeAdapter` 或明确逐 Part 提取实际 instructions、文本、Tool args/return、Retry 内容。不要序列化整个 RequestContext、model/provider 或运行 deps，避免把凭据和客户端对象送入检测器。回归必须使用真实框架消息形状。
- **_save**（[P/agent/growth_investigator.py:126](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:126)）：安全策略来自 guardrail，但“将 Java 事实压成 Observation → 检查 → 记录 Evidence → 返回 ToolObservation”的编排属于 Tool 适配，不能整体塞进 security。
- **prepare_proposal / persist_campaign_proposal**（[P/agent/growth_investigator.py:351](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:351)）：前者是动态能力可见性，后者再次核对 Investigation/一次性生成/当前 Evidence/精确授权事实，是必要的执行边界。两次检查目的不同，不是应删除的重复。身份来自 deps，不开放为模型 Tool Schema 参数。
- **validate_diagnosis / _validate_evidence**：业务证据不变量，移为 Domain 纯函数/方法并由 Agent 把领域失败转换为 ModelRetry；PII 检查在独立安全边界执行。既不能只检查 UUID 结构，也不能把合法 ID 宣称为声明内容已获证明。
- **CampaignDslValidator / FieldRegistry / Converter**：Java 最终业务规则，必须在 preview、create/edit、confirm 各自入口执行。重复执行反映不同信任边界和时点；F15–F17 应在 Java 修复，不能关闭 Java 校验以“信任 Python”。
- **FrequencyControlService.check**：业务触达额度的原子预留，不属于 PII 或身份模块。继续在实际发送前执行；Redis 失败拒绝发送的保护保留，但应将临时不可用与真实额度用尽区分，以选择重试还是业务取消。
- **AgentToolAuthFilter / assertOwner / lockOwner / requireDraftOwner**：机器认证、领域授权和事务内归属锁分别保留。将它们放进一个泛化 SecurityManager 会掩盖真实依赖和事务语义。

### C.3 非可信输出、日志与流的安全顺序

建议顺序为：

~~~text
机器/会话认证
 → Java 资源授权（浏览器请求）
 → 输入 Schema + 请求准入
 → PII/业务敏感字段检查
 → 创建/恢复受控运行
 → 每次模型请求检查实际将发送的消息
 → 模型响应检查（包括后续重试/Tool 参数）
 → Tool Schema + 领域约束
 → Java 只读 Tool 再验证参数并查询聚合
 → Typed response + 请求/响应语义对应 + Observation PII
 → Evidence / Hypothesis / Proposal 写入前规则和完整内容检查
 → Diagnosis 引用检查 + 最终内容检查
 → 事务化终态
 → 公开 DTO / 安全 SSE / 白名单 Trace 和结构化日志
~~~

本轮没有建议把 Java 业务表权限给 Python、把 SQL/原始事件发给模型、开放执行 Tool、展示 Chain of Thought，或用前端禁用按钮代替后端授权。

SSE 应继续只传业务事件和标识；现有 Web 只因事件刷新快照的设计可保留。Java 转发器必须在读取中限制帧大小并在浏览器断开时关闭上游；Python 的连接计数应覆盖响应/生成器的整个生命周期。上游身份/ID 与请求 ID 的对应验证属于协议防线，内容判断仍归安全策略。

安全失败不一定需要同一响应码：认证 401、归属 403、Schema/PII 拒绝、业务状态冲突、容量 429、依赖暂不可用应保持可区分固定代码。未提交的业务写入必须回滚，已提交 Java Draft 的 Python 关联失败不能假装全回滚。

## D. 启动与运行生命周期专项报告

### D.1 当前及建议所有者

| 阶段 | 当前位置/方法 | 建议所有者 | 核验与失败/关闭要求 |
|---|---|---|---|
| 1. 配置读取和校验 | AgentSettings；main.lifespan | config.py 保留 | 缺 DB/真实模型密钥/Azure/生产专用 schema 等启动失败；不要读取配置时开启网络连接。validate_real_model 名称可改 validate_configuration，当前实际也检查 DB/环境。 |
| 2. 启动前置检查 | Repository.check_ready/recover_interrupted；迁移由 Alembic/Compose | 存储实现提供检查，bootstrap 调用 | 生产先迁移再启动；检查支持版本/表列。recover_interrupted 只在一进程一 schema 契约下安全，不当成每个请求的恢复步骤。 |
| 3. 安全配置检查 | AgentSettings.validate_real_model + lifespan token 检查 | 配置规则保留、Bootstrap 组织 | 各环境使用内部 Tool 都需要 token；生产不能 test model，Azure 需 HTTPS。不要通过启动时发付费文本测试替代配置检查。 |
| 4. 资源初始化 | engine、httpx、Telemetry、OpenAIProvider | 各实现/框架 context manager | Repository 文件可提供存储 context；httpx/Provider/Telemetry 各自拥有释放能力；取得资源后立即登记退出栈，覆盖中途失败。 |
| 5. 依赖组装 | main 构造 Repo、Client、Guard、Agent、Service | bootstrap.py | 注入同一 settings/明确 RunAdmission，禁止读取 Agent 私有字段；返回小型资源集合或 app.state 所需对象，不造容器/自动扫描 DI。 |
| 6. Agent 创建及 Tool 注册 | create_model/create_usage_limits/GrowthInvestigator.__init__/_register_tools | Agent 模块；可聚合提取 tools.py | create_model 是单 Provider 工厂，可保留；create_usage_limits 是框架配置映射，可留 Agent 模块，不为八行新建文件。注册钩子/工具并进入官方 async Agent context。 |
| 7. 请求接入与运行准入 | main routes；Service.start/_run；Java GatewayLimit | API 做协议；Service 统一执行准入 | Java 登录/owner 在跨服务调用前；名额获取后外部前检/工作区初始化/执行均有期限；就绪、并发、业务可恢复状态不是同一个 check。 |
| 8. Agent 执行 | GrowthInvestigator.run + 原生 Pydantic AI loop | GrowthInvestigator | 只负责框架执行和返回诊断/官方 Usage，Tool 负责业务适配；请求/Tool/Token 限制不另造实现；每次请求和 Tool 边界保留安全调用。 |
| 9. 结果持久化与错误收敛 | Workspace.finish + Repo.finish；Agent/Service 部分兜底 | Service 组织；Workspace 会话操作；Repo 事务 | 无论失败发生在工厂、add_message、模型、最终 PII 或 finish，都要有明确状态。终态 CAS + 助手消息同事务；存储完全不可用则安全记录未收敛，启动恢复不能伪称本次已保存。 |
| 10. 关闭 | Service.shutdown → client close → engine.dispose → Telemetry.shutdown | Bootstrap 退出栈 + Service | 停止准入、取消/等待全部受管运行与 SSE、限时收敛，再释放模型/HTTP/DB，最后刷新关闭遥测。只取消后台 _tasks 不等于同步请求和模型资源都已释放。 |

### D.2 运行状态与诊断状态

`Diagnosis.status` 的 `DIAGNOSED/INSUFFICIENT_EVIDENCE` 描述结论质量；`Investigation.status` 还描述 `RUNNING/COMPLETED/FAILED/CANCELLED/BUDGET_EXHAUSTED`。这两个概念应保留区别，**不建议强行合并成一个枚举或立即改数据库/API**。

当前 Service.release 用 result.diagnosis.status，Telemetry 用运行 status，形成口径不一致；timeout 借 CancelledError 收敛为 CANCELLED，测试也明确断言该行为。修复时需先保持既有 wire 状态，至少在内部运行结果中区分取消原因（用户取消、总体期限、关闭），统一状态投影和指标；若新增 TIMED_OUT wire 状态，属于另行批准的协议变更，不作为纯迁移夹带。

begin_followup 保留上一轮 final_diagnosis，Web 在 RUNNING 时标“上一轮诊断”，这一行为有用途，应保留。新范围不能因此把历史 Evidence 重新视为当前有效证据。

### D.3 Workspace 与事务的一致性

| 操作 | 不变量/应用责任 | SQL 责任 | 内存与失败处理 |
|---|---|---|---|
| create/begin_followup | Service 做请求安全/准入/运行登记 | Repository 插入；允许终态 → RUNNING 的 CAS | 取得 ID 后就进入统一错误收敛范围；工厂失败不能遗留无 task RUNNING。 |
| record_tool/add_evidence | Tool 选择来源、Observation、安全检查与起始 scope | 轨迹锁行追加；Evidence insert + 调查 updated_at 同事务 | DB 成功再更新内存的现有顺序保留；版本需在请求开始与写入时核对。 |
| propose/update_hypothesis | 当前 Evidence、支持/反对不重叠、SUPPORTED/WEAKENED 的证据要求 | insert/update + 调查更新时间；归属和版本条件 | 同一 Workspace 的变更采用顺序约束；失去版本一致性时终止/重载，不继续用旧列表向模型报告成功。 |
| change_scope | Scope 版本提升、旧假设降为历史 | CAS + 假设淘汰同事务 | 当前 proposals 未随范围淘汰，内存 proposals 也未变化。历史 GENERATED 是否允许继续生成 Draft 属 V03 策略事项，不能只改 UI。 |
| add_proposal | 身份/授权事实来自可信上下文，当前合法 Evidence，完整 PII | Agent 独立 Proposal 记录，owner/scope/status | 不能仅靠 deps.proposal_created 布尔值保证跨请求幂等；现状是每轮最多一次，不是重复 HTTP 请求全局最多一次。若需请求去重，明确协议和唯一约束。 |
| finish | Service 决定运行终态，Diagnosis 内容已检查 | RUNNING → 终态 + ASSISTANT 消息同事务 | 保留 SQL 原子性；内存更新失败/提交结果未知时中止该会话，重新 load 后决定状态，不能盲目再次写入另一个终态。 |
| Java Proposal → Draft | Java owner/status/evidence/DSL/preview 最终校验 | owner 行锁、Draft request_id 唯一键、链接同事务 | Java commit 后再回写 Python；失败可重试修复，不删除已提交 Draft。 |
| Java Draft confirm | 同一个草稿最多确认一次、并发编辑不可覆盖终态 | 锁定读/CAS、Campaign+Rule+Draft 同事务 | 失败标记和抛异常回滚的策略需显式设计；不可把整个 LLM/跨服务过程包进该事务。 |

没有建议为全轮 Agent 执行持有长数据库事务。Repository 每个业务写单元的事务有价值；“为了统一”把 90 秒模型调用放进一个事务会扩大锁竞争和失败面。

## E. 推荐目录、类结构与提案决策

以下为可一次实施的相关结构，标记“可选”的提取以 F08/F26 的实际修改范围为前提。保留现有表名、API 路径、JSON aliases 和 Tool 名；对真实缺陷需要的行为变化在 B/G 单独说明。

### E.1 Python

~~~text
pulseflow-agent/src/pulseflow_agent/
├── __init__.py
├── main.py                              保留 app=create_app() 及极薄导出
├── api.py                               create_app、请求 DTO、路由/错误映射、健康、业务 SSE
├── bootstrap.py                         lifespan、资源退出栈和显式依赖装配
├── config.py                            AgentSettings 与纯配置规则
├── runtime.py                           RunAdmission、AdmissionRejected；有界运行控制
├── application/
│   └── investigations.py                InvestigationService，所有同步/后台运行与 Proposal 入口
├── agent/
│   ├── growth_investigator.py            Agent 组装、模型调用、官方生命周期；create_model/usage 映射
│   ├── tools.py                         可选聚合 Tool 注册、_save、prepare_proposal、校验器适配
│   ├── dependencies.py                  真实每轮依赖、ProposalContext；去掉无使用 OperatorContext
│   ├── instructions.py                  固定指令 + build_investigation_context
│   └── observations.py                  现有五个纯 Observation 构造器
├── domain/
│   ├── investigation.py                 DTO、状态类型及纯证据/假设/诊断规则
│   ├── campaign_proposal.py              方案/记录 DTO、UUID 结构规则；不承担 PII Provider
│   └── contracts.py                     现有受限 Java wire 类型、Schema 规则、safe_query
├── workspace/
│   ├── session.py                       InvestigationWorkspace，一个可变持久化会话
│   ├── repository.py                    现有 Protocol + SQL 实现 + 存储 context/就绪/恢复
│   └── models.py                        现有 SQLAlchemy 元数据
├── clients/
│   └── pulseflow_api.py                  Java HTTP、固定错误、熔断、结构/语义契约
├── security/
│   ├── auth.py                          内部 Token 策略与中间件/注册适配
│   └── pii_guardrail.py                  现有本地/Azure策略 + 官方模型钩子适配
└── observability/
    └── tracing.py                       Telemetry、官方 instrumentation、exporter、质量统计
~~~

新增/迁移理由：

- `api.py` 和 `bootstrap.py` 分离协议变化与初始化组织；不创建只有几个小模块的空 `api/runtime/bootstrap` 包层级。main 保留导出可避免 Uvicorn 入口协议变动。
- `application/investigations.py` 的 Service 真实跨越运行、Domain、持久化和 Agent，属于应用用例；不再把全部应用行为解释成“Repository 的附属物”。
- `workspace/session.py` 迁移整个 Workspace，保留高度相关方法集中。领域纯不变量可复用到输出校验，Prompt 渲染移至已有 instructions 文件。不是“拆出十个一方法类”。
- `agent/tools.py` 是一个聚合模块，保留每个带类型 Tool 函数和 docstring。若不做 F08 提取，也必须修复 F01–F07；是否提取不影响安全门。
- Security 中 auth 与 PII 是不同策略；保留各业务边界的主动调用，不让 security 调用应用 Service 或数据库。模型钩子和 Azure 策略放在同一现有文件即可，无需 GuardrailFactory/Manager。
- create_model 和 create_usage_limits 均有真实使用，单 Provider 下可原位保留；不建议抽象 ModelProviderFactory 基类、通用 ExecutionEngine 或自研 UsageCounter。
- Repository Protocol 已有真实的持久化边界和替身用途，保留。表定义、迁移、Repository 不能搬进 Domain；contracts 虽含 wire 类型但目前是纯 Pydantic 定义，不因目录形式强制再拆成多个相同 DTO 层。
- 本轮没有提出新增 `model/messages/history/retry` 子系统，优先利用已安装 Pydantic AI 的 hooks、UsageLimits、sequential Tool 和 async context manager。

推荐依赖图：

~~~mermaid
graph TD
  Main["main"] --> API["api"]
  API --> Bootstrap["bootstrap"]
  API --> App["application / InvestigationService"]
  Bootstrap --> App
  Bootstrap --> Resources["Repository / HTTP / Telemetry / Settings"]
  App --> Agent["agent"]
  App --> Session["workspace.session"]
  App --> Repo["workspace.repository"]
  App --> Runtime["runtime"]
  Agent --> Tools["agent.tools / instructions / observations"]
  Tools --> Session
  Tools --> Client["clients"]
  Tools --> Security["security"]
  Agent --> Security
  Repo --> Domain["domain"]
  Session --> Domain
  Session --> Repo
  Client --> Domain
  Security --> Config["config"]
  Runtime --> Config
~~~

`Repository` 不导入 Session/Application/Agent；`Domain` 不导入 Repository/OTel/Agent；`Security` 不反向调用业务。Session → Repository 可是 Protocol 类型依赖；Repository → Domain 只重建 DTO。因此迁移后不会制造 Domain ↔ Workspace 的运行环。

### E.2 Java

~~~text
pulseflow-boot/src/main/java/com/pulseflow/boot/
├── PulseFlowApplication.java
├── config/                              Kafka、Redis、MyBatis、XXL、会话存储和 Telemetry 装配保留
├── security/
│   ├── AgentToolAuthFilter.java          从 agenttools 迁入
│   ├── AgentToolAuthConfig.java          从 agenttools 迁入，路径与 order 不变
│   ├── SaTokenConfig.java               会话路由准入
│   └── LocalAuthProperties.java          从 web 迁入；身份配置仍不等于资源授权
├── web/
│   ├── AuthController.java
│   ├── DevLoginController.java           从 config 迁入，仍默认关闭
│   ├── WebQueryController.java
│   ├── WebDtos.java                      相关 record 可继续集中
│   ├── CampaignQueryService.java         原 WebQueryService Campaign 读模型与映射
│   ├── UserQueryService.java             用户/画像读模型与映射
│   ├── ActivityQueryService.java         Event/Delivery/Attribution 观察读模型
│   └── DashboardQueryService.java        运营汇总、趋势、健康投影适配
├── agenttools/
│   ├── AgentToolController.java
│   ├── AgentToolDtos.java
│   ├── AgentToolService.java
│   ├── AgentMetricService.java
│   ├── AgentMetricReadRepository.java    可选固定 SQL 读取协作类
│   └── AgentToolExceptionHandler.java    继续固定码；更新 advice 的 Controller 类型
└── investigation/
    ├── InvestigationController.java     原 AgentProposalGateway 的 MVC 部分
    ├── InvestigationApplicationService.java  owner/准入/跨服务创建协调
    ├── AgentInvestigationClient.java     原 Gateway.call/url/HTTP 配置
    ├── InvestigationEventRelay.java      原 Gateway.events/shutdown 的上游流资源
    ├── AgentInvestigationOwnership.java  从 agenttools 迁入，Java 业务授权/行锁
    ├── AgentProposalClient.java          从 agenttools 迁入；固定 HTTP 传输
    ├── CampaignProposalDraftController.java
    ├── ProposalDraftApplicationService.java  事务外快照与提交后关联修复
    └── CampaignProposalDraftService.java     保留 Java 事务化最终写入

pulseflow-profile/src/main/java/com/pulseflow/profile/
├── service/
│   ├── ProfileService.java              画像读取及精度/缺失语义
│   ├── UserPreferenceService.java       业务免打扰/退订/已转化
│   ├── RealtimeProfileUpdateService.java 从 campaign.profile 原样迁入后修复时间/TTL
│   ├── WindowMetricService.java         原 WindowMetricJob 的业务计算和保存
│   └── TagRecalculationService.java     原 TagRecalcJob 的业务选择/计算/保存
└── tag/strategy/                        现有八种 TagStrategy 保留，统一标签契约

pulseflow-event/src/main/java/com/pulseflow/event/
├── controller/EventController.java
├── service/
│   ├── EventService.java                HTTP 事件规范化、Kafka 接入
│   ├── EventPersistenceService.java     独立代理的事件/小时桶事务
│   └── EventProcessingService.java      正常与补偿复用的画像/决策/归因/点击编排
├── consumer/EventConsumer.java          Kafka 适配、解析和委托
└── config/KafkaConsumerConfig.java

pulseflow-campaign/src/main/java/com/pulseflow/campaign/
├── dsl/ validation/                     保留 DTO/注册表/校验转换，兑现 Java 语义
├── draft/                               DraftController/Service/Mapper/实体，补锁/CAS
├── preview/                             PreviewService/SQL实现/结果，统一最新标签语义
├── decision/DecisionEngine.java         业务决策，显式失败分类
├── delivery/
│   ├── DeliveryService.java             持久化任务与发布/补发契约
│   ├── DeliveryConsumer.java            现有受控消费/渠道步骤，不先引入 Sender 抽象工厂
│   ├── DeliveryRecoveryService.java     原 RetryCompensationJob 的 Delivery 状态恢复
│   └── FrequencyControlService.java     原子业务额度预留
├── scheduling/CampaignExecutionService.java  调度领取/实例事务、执行/重试状态
├── delay/                               现有 DelayedTaskManager/Executor 保留
├── attribution/                         业务归因、点击、消费者及自身租约恢复
└── analytics/CampaignPerformanceSummaryService.java  原 Calculator 的计算/刷新/封存

pulseflow-job/src/main/java/com/pulseflow/job/
├── handler/                             XXL Handler 委托以上业务服务
└── service/CompensationClaimService.java  保留真实事务领取边界；重试状态归执行协调

pulseflow-common/                        现有实体、Mapper、公共 DTO/枚举/JSON/dedup 保留
pulseflow-simulator/                     保留独立非默认打包定位，部署选择见 G.4
~~~

Java 新类的边界理由：

| 类/组 | 新增、迁移或保留原因 | 避免的复杂度 |
|---|---|---|
| InvestigationController / Client / EventRelay / ApplicationService | 协议、HTTP、流资源和 owner 跨服务状态分别变化；源于 Gateway 已存在的真实职责 | 不再额外套 AgentGatewayFacade、TransportFactory 或 BaseController |
| ProposalDraftApplicationService + 现有事务 Service | 两个数据库无原子事务，提交前/提交后的失败含义不同；独立 Bean 保证代理事务有效 | 不把网络回写放进 Java 事务，不引入分布式事务框架 |
| Ownership | 访问策略及 FOR UPDATE 同属调查业务归属，小型 JDBC 实现可保留同类 | 不为了 60 行再造接口/Repository/Authorizer 三层 |
| 四组 Web QueryService | 原类有跨功能变化、N+1 与测试依赖证据，按读模型拆 | 映射与查询组内内聚，不为每个 DTO 建 mapper/assembler 类 |
| MetricReadRepository（可选） | 固定 SQL 事实读取可独立测试，公开 API 元数据不属于查询层 | 保留 Service 参数约束，不建空接口，不将 boot DTO 倒灌 campaign |
| EventProcessingService | 正常消费/补偿两个真实入口复用完整阶段，修复归因/点击断链 | 不把 Consumer 本体互相调用，不为每个阶段造抽象流水线 |
| Profile 的两种计算 Service | 画像定义/范围/保存属于 Profile，Job 提供调度入口 | 八种真实策略已足够，不自建策略工厂；小型 DailyMetricJob 单 SQL 可保留 |
| CampaignExecutionService / DeliveryRecoveryService | 需原子状态迁移、可单测及故障恢复，不是缩短 Handler 行数 | 事务方法须从外部 Bean 调用或用 TransactionTemplate，避免新一轮 self-invocation |
| CampaignPerformanceSummaryService | 原 Calculator 实际读写/复用数据库状态，名称应反映服务和封存策略 | 不把 200 行拆成 Calculator/Factory/Writer/Repository 五层 |
| common、DTO、静态工具、delay/attribution 现有能力 | 当前是模块化单体共享数据库，真实业务复用充分 | 不大搬 58 个 common 文件、不强行富领域化所有实体、不因 Lua 类似就统一不同队列语义 |

### E.3 Web、测试与部署

~~~text
pulseflow-web/src/
├── api/                                 现有各业务 HTTP/SSE 适配保留
├── composables/
│   ├── useInvestigationSession.ts        load/refresh/start/follow-up/cancel/SSE/请求版本
│   └── useDraftReview.ts                 Proposal/Draft/preview/confirm 的异步身份和状态
├── views/Investigations.vue              表单、对话、证据/假设/诊断、人工确认的展示
├── views/其他详情与列表                   参数 watch、过期响应丢弃；按需复用小型请求控制
├── stores/auth.ts router/                仍负责会话恢复与导航准入
└── components/ types/ utils/ styles/     保留；指标契约对齐，演示数据只在 demo 模式使用

testing/
├── functional/generate.py replay.py state.py validate.py
│                                        保留数据生成、接入、隔离清理、最终状态验收分工
├── functional/测试                      补无证据 NOT_RUN 和业务语义 oracle
├── performance/                         k6 保留独立性能职责
└── agent_smoke.py / Compose / CI         增强真实契约路径，保留隔离栈
~~~

这里的 composable 提取来自 F38 的任务身份/取消问题；不要求将每个 Evidence 卡片、按钮或普通页面拆文件。当前 Vue API 小文件代表不同资源协议，有调用方且使页面不依赖 Axios 细节，应保留。

### E.4 架构决策记录（提案，未实施）

- **D01：保持单 Pydantic AI Agent，按边界提取协作。** 选择框架 Hooks/UsageLimits/sequential/async context；不选择自研执行器、多 Agent 或泛化工厂。收益是关闭已证实安全/并发窗口；代价是需冻结 Tool Schema 和补钩子回归。
- **D02：Workspace 作为应用会话，Domain 保存纯规则。** 不选把整个 Workspace 拆成多个 Manager，也不选把 Repository/Trace/Prompt 统称领域。保留 DB 成功后更新内存及单写单元事务，错误由统一用例收敛。
- **D03：Java 保留业务写入权威，跨服务采用显式提交后协调。** 不选择 Python 直接建/确认 Draft，也不选择网络请求与两个数据库“共享事务”。现有幂等 Draft 和提交后修复保留，增加可观测恢复。
- **D04：职责调整不默认改表/协议。** 类/方法迁移可一次落地；OR/Decimal/频控/ONCE 等缺陷以可执行语义为标准修复。CLICK 幂等若需要新唯一来源列必须另加迁移，历史迁移和旧表不直接删。新增 wire 状态/请求幂等身份也必须显式列为协议变化。

以上是供后续评审的审计提案，没有将重构设计伪装成已获批准或已经实施。

## F. 重点方法审查与迁移映射

分类：**保留**（职责合理，可能原位修复缺陷）、**原样迁移**（所有者/包改变但行为不变）、**重构拆分**（将当前方法的不同阶段交给适当所有者）、**合并复用**（消除已有同职责漂移）、**删除候选**（须有调用证据和兼容性核查）。目标文件尚未创建，均以代码文字表示；不是本轮变更清单。

### F.1 Python Agent：逐个 Tool 与执行方法

以下各 Tool 迁移时必须保留对模型公开的函数名、参数名、类型、docstring、返回形状和 prepare/sequential 语义。目标 `agent/tools.py` 的 `register_tools` 可以继续用局部装饰函数，不必创造 Tool 类继承体系。

| 当前文件 / 类 / 方法 | 目标文件 / 类或函数 / 方法 | 分类 | 实际职责、所属理由与依赖 |
|---|---|---|---|
| [P/agent/growth_investigator.py:56](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:56) / create_model | agent/growth_investigator.py / create_model | 保留 + 生命周期修复 | 只构造框架模型/Provider、设置离线结果和禁 SDK 隐式重试；属于 Agent 组装。依赖 Settings/Pydantic AI/OpenAIProvider，不应混业务/仓储。单 Provider 无需抽象工厂。 |
| [P/agent/growth_investigator.py:90](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:90) / create_usage_limits | 同文件 / create_usage_limits | 保留 | 将配置映射为官方 UsageLimits；每轮调用，不能启动一次后共享可变 Usage。无必要为该简单映射新建 runtime/usage.py。 |
| [P/agent/growth_investigator.py:102](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:102) / GrowthInvestigator.__init__ | 同类 __init__、新增公开 async 生命周期适配 | 重构拆分 | 保留 Agent 的 output_type/deps_type/instructions/retries/name/capabilities 组装；工具注册可委托一个聚合函数。资源关闭委托 Agent 原生 context。 |
| [P/agent/growth_investigator.py:126](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:126) / GrowthInvestigator._save | agent/tools.py / save_observation | 原样迁移 + F04 修复 | 不使用 Agent 自身状态；把权威响应、Observation 安全检查、Evidence 保存和 Trace 关联编排为 Tool 输出。依赖 RunContext/Workspace/Guard，不属于 Provider 或 Security 策略。 |
| [P/agent/growth_investigator.py:162](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:162) / investigation_context | agent/instructions.py / build_investigation_context；tools 注册适配 | 重构拆分 | 渲染近期消息/证据/假设/授权优惠事实，发往模型前调用安全能力。格式属于 Agent 提示上下文，不是 DB/Domain。 |
| [P/agent/growth_investigator.py:176](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:176) / query_metric | agent/tools.py / register_tools.query_metric | 原样迁移 + 版本核验 | 记录轨迹、调用 Java Client、生成 query Observation、保存 Evidence，固定错误无 Evidence。它是模型 Tool 适配，不应被 HTTP Client 吸收领域写入。 |
| [P/agent/growth_investigator.py:191](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:191) / compare_metric | agent/tools.py / register_tools.compare_metric | 原样迁移 | 两期查询和 Java delta 观察；保留 Java 计算差值，Python 不重新计算指标。 |
| [P/agent/growth_investigator.py:206](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:206) / breakdown_metric | agent/tools.py / register_tools.breakdown_metric | 原样迁移 | 单维度拆解适配；具独立 Schema/语义，不用字典循环生成通用 **kwargs 工具。 |
| [P/agent/growth_investigator.py:221](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:221) / get_campaign_performance | agent/tools.py / register_tools.get_campaign_performance | 原样迁移 | 权威只读摘要 → Observation/Evidence；不能因没有摘要就调用 Java Calculator 写库。 |
| [P/agent/growth_investigator.py:244](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:244) / get_attribution_breakdown | agent/tools.py / register_tools.get_attribution_breakdown | 原样迁移 | 聚合归因适配，保持无用户明细和 UUID 来源元数据。 |
| [P/agent/growth_investigator.py:263](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:263) / preview_audience | agent/tools.py / register_tools.preview_audience | 原样迁移 + 输入安全修复 | 完整 DSL 出站保护；Java 负责规则校验/人数。safe_query 仍只保存安全聚合选择摘要，不存整段自由文本。 |
| [P/agent/growth_investigator.py:278](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:278) / propose_hypothesis | agent/tools.py / register_tools.propose_hypothesis | 原样迁移 | ModelRetry 是框架适配；statement/reason 内容检查留 Tool，Evidence/集合规则委托 Domain/Session。OPEN 可无证据，不能机械给所有引用列表加 min=1。 |
| [P/agent/growth_investigator.py:297](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:297) / update_hypothesis | agent/tools.py / register_tools.update_hypothesis | 原样迁移 + 写顺序修复 | 可信当前范围内更新；SUPPORTED/WEAKENED 的证据要求是领域规则，非法引用转稳定 ModelRetry，基础设施异常不能当引用错误吞掉。 |
| [P/agent/growth_investigator.py:325](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:325) / list_hypotheses | agent/tools.py / register_tools.list_hypotheses | 原样迁移 | 返回已有调查状态；返回模型前内容保护。读取保留历史假设有价值，更新仍必须限制当前范围。 |
| [P/agent/growth_investigator.py:340](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:340) / update_scope | agent/tools.py / register_tools.update_scope | 原样迁移 + sequential | PII 检查与 ModelRetry 适配；范围版本/淘汰由 Session/Repository 负责。不能和只读 Tool 并发改变采证归属。 |
| [P/agent/growth_investigator.py:351](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:351) / prepare_proposal | agent/tools.py / register_tools.prepare_proposal | 原样迁移 | 利用官方 prepare 动态隐藏无授权、无当前证据或已生成的能力；不是新造运行准入。 |
| [P/agent/growth_investigator.py:366](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:366) / persist_campaign_proposal | agent/tools.py / register_tools.persist_campaign_proposal | 原样迁移 + 完整内容检查 | 再验证可信授权、当前 Evidence 和 exact facts 后保存；维持 sequential=True 和每轮布尔标志。Java 最终 Draft 授权仍独立执行。 |
| [P/agent/growth_investigator.py:388](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:388) / validate_diagnosis | domain/investigation.py / validate_diagnosis_references；Tool 层适配器 | 重构拆分 | 将纯引用/证据不足规则与 ModelRetry/metrics 分开；输入 known_evidence_ids，不让 Domain 依赖 RunContext/Telemetry/Workspace。 |
| [P/agent/growth_investigator.py:413](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py:413) / GrowthInvestigator.run | Agent.run（框架执行/官方用量）；Application Service.run（生命周期） | 重构拆分 | 移出 workspace_factory、USER 消息、finish/取消/失败持久化；Agent 接收显式每轮 Workspace，返回经过检查的诊断和官方 Usage/成本。保留框架的动态 Tool/Retry，不重写内部循环。 |
| [P/agent/observations.py:13](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/observations.py:13) / _dimensions、五个 observation 函数 | 原文件 | 保留 | 紧凑、可追溯的 Java 结果描述器，高内聚纯转换；不做 SQL、业务重算或数据库写入，也无需一函数一文件。 |

这些方法当前围绕“调查”有业务关联，F08 的提取理由是依赖方向和运行故障收敛，不是方法数量。正常 ToolClientError 仍返回固定 Observation；PII、存储、取消等不能被泛化 catch 成普通无证据成功。

### F.2 Python Workspace、Domain、Service 与基础设施

| 当前文件 / 类 / 方法 | 目标文件 / 类或函数 / 方法 | 分类 | 职责、归属与失败约束 |
|---|---|---|---|
| [P/domain/investigation.py:122](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:122) / InvestigationWorkspace | workspace/session.py / InvestigationWorkspace | 原样迁移为主 | 一个每轮持久化会话集中维护 Evidence/Hypothesis/Message/Proposal 列表，保留 DB 后更新内存；不再把整个类称纯 Domain。 |
| [P/domain/investigation.py:138](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:138) / add_proposal | workspace/session.py / add_proposal | 原样迁移 | 会话创建 UUID/owner/scope/status，调用仓储后更新列表；内容/授权由调用边界保证，引用规则纯 Domain。 |
| [P/domain/investigation.py:159](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:159) / record_tool | workspace/session.py / record_tool | 原样迁移 | 进度轨迹持久化与内存同步，供 SSE/恢复使用，不是日志模块。 |
| [P/domain/investigation.py:165](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:165) / add_evidence | workspace/session.py / add_evidence | 重构拆分 | 会话维护 Evidence 与 scope；Trace ID 在 Tool 中取得并显式传入，safe_query 可保留为纯安全选择摘要；补开始版本/写入版本核验。 |
| [P/domain/investigation.py:189](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:189) / propose_hypothesis | workspace/session.py / propose_hypothesis；Domain 纯规则 | 重构拆分 | 会话创建/保存对象；当前 Evidence、互斥引用属于领域不变量。不得让 Repository 承担模型文本安全。 |
| [P/domain/investigation.py:218](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:218) / update_hypothesis | workspace/session.py / update_hypothesis；Domain 纯规则 | 重构拆分 | 对象状态变化与持久化同步仍同一会话；数据库归属/版本条件保留，顺序冲突不能覆盖内存旧值继续执行。 |
| [P/domain/investigation.py:255](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:255) / change_scope | workspace/session.py / change_scope | 原样迁移 + 策略核验 | 范围提升和旧假设历史化高度相关，可同类集中；SQL CAS 与假设淘汰仍在 Repository。Proposal 的历史使用策略见 V03。 |
| [P/domain/investigation.py:281](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:281) / add_message | workspace/session.py / add_message | 原样迁移 | 保存可见消息而非 Pydantic AI 原始模型 history；保持 role/content/diagnosis wire 行为。 |
| [P/domain/investigation.py:295](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:295) / finish | workspace/session.py / finish；由 Service 调用 | 原样迁移 + 终态所有权调整 | Session 执行已决定的终态写入，Service 负责为何、何时、失败如何处理；Repo 同事务写状态和助手消息。 |
| [P/domain/investigation.py:309](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:309) / context_text | agent/instructions.py / build_investigation_context | 原样迁移 | 近期条目选择、CURRENT/BACKGROUND、字符截断是模型上下文策略，不是存储模型。保持现有内容/截断先建立回归，再改善裁剪。 |
| [P/domain/investigation.py:336](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:336) / _validate_evidence、evidence_ids | Domain 引用规则 + Session.current_evidence_ids | 合并复用 | 已知/历史 ID 判断供 Hypothesis、Diagnosis、Proposal 共用，避免每层不同集合规则；纯函数只接 IDs/范围，不接 DB/LLM。 |
| [P/domain/investigation.py:29](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py:29) / Evidence/Hypothesis/AgentMessage/Finding/Diagnosis/Investigation(Result) | domain/investigation.py | 保留 | 纯 Pydantic 数据与语义类型；普通 DTO 集中有利于契约，Getter/序列化不逐条搬迁。 |
| [P/domain/campaign_proposal.py:33](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/campaign_proposal.py:33) / valid_references | 原方法 | 保留 | UUID/重复引用的结构规则；合法归属、当前范围和授权事实不能只由此方法证明。 |
| [P/domain/campaign_proposal.py:41](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/campaign_proposal.py:41) / free_text | 完整模型检查；free_text 若不再有调用则删除候选 | 合并复用/删除候选 | 手工 PII 投影易遗漏；Schema/业务对象不维护云 Provider。实际删除前更新调用方与缺字段回归。 |
| [P/domain/campaign_proposal.py:53](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/campaign_proposal.py:53) / to_dsl | 原方法或删除 | 删除候选 | 无仓库调用；Java 从持久化 Proposal 构造权威 DSL，不能新增调用作为绕过 Java 的快捷写入。 |
| [P/domain/contracts.py:63](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/contracts.py:63) / TimeRange.bound_window | 原方法 | 保留 | 带时区、正跨度/最大窗为 Tool Schema 规则；Java 独立验证是另一边界，并非应合并的重复。 |
| [P/domain/contracts.py:78](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/contracts.py:78) / MetricFilter.valid_values | 原方法 | 保留 | 筛选值类型/白名单，避免模型传任意表达式；SQL 参数绑定仍不可删除。 |
| [P/domain/contracts.py:111](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/contracts.py:111) / CompareMetricArgs.no_day_comparison | 原方法 | 保留 | 比较对齐能力限制，属于业务/契约，不属于身份或 PII。 |
| [P/domain/contracts.py:309](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/contracts.py:309) / safe_query | 原文件（或随纯契约命名调整） | 保留 | 控制 Evidence 存储选择摘要，对 Preview 排除自由文本 DSL；这是纯转换，不需安全 Manager。 |
| [P/workspace/service.py:24](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:24) / InvestigationService.__init__ | application/investigations.py / __init__ | 原样迁移 + 显式注入 | 接 Repository、Agent、Guard、RunAdmission，不读取 Agent 私有 settings。无需新增抽象 Service 基类。 |
| [P/workspace/service.py:37](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:37) / _run | application/investigations.py / execute_run | 重构拆分/合并复用 | 统一准入、期限、任务登记、输入/工作区初始化、框架执行、终态/异常/取消与释放。状态来源用运行 outcome，不直接用 Diagnosis.status。 |
| [P/workspace/service.py:59](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:59) / start；factory/background/done | 同 Service / start + 共用 execute_run | 重构拆分 | 只决定后台提交并返回快照；后台和同步都在共用生命周期内。done 清理要对应实际 Task，消费异常同时保留安全可观测失败码。 |
| [P/workspace/service.py:117](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:117) / _stopped_diagnosis | 同 Service（或 Domain 的中断结果构造） | 保留/合并复用 | 与 FAILED/CANCELLED/BUDGET 的固定安全诊断生成合并，避免 Agent/Service 多份文案和原因映射漂移；无需新建单函数文件。 |
| [P/workspace/service.py:129](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:129) / cancel | 同 Service / cancel | 原样迁移 + 覆盖全部运行 | 取消本进程拥有的任务并等待有界收敛；不同进程冲突不能伪造取消成功。同步路径也应可登记/退出。 |
| [P/workspace/service.py:145](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:145) / shutdown | 同 Service / shutdown | 原样迁移 + 有界清理 | 停止准入、取消/等待拥有的运行；底层资源关闭交 Bootstrap。 |
| [P/workspace/service.py:157](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:157) / _workspace | 同 Service / _workspace | 保留 | 持久化快照 → 每轮会话，是应用组装；不移到 Repository 使其依赖可变 Session/Agent。 |
| [P/workspace/service.py:173](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:173) / create | 同 Service / create | 原样迁移/合并复用 | 同步新调查用例，委托共用执行入口；不再让 Agent 的工厂回调控制持久化时点。 |
| [P/workspace/service.py:183](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:183) / follow_up | 同 Service / follow_up | 原样迁移/合并复用 | 同步追问和 scope 用例，恢复 CAS/前检/错误收敛采用统一规则。 |
| [P/workspace/service.py:200](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:200) / get | 同 Service / get | 保留 | 简单委托有明确 API/应用边界，不能只因一行就让路由直接访问 Repository。 |
| [P/workspace/service.py:204](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py:204) / propose | 同 Service / propose | 原样迁移 + 原子前提复核 | 检查完成诊断/当前证据和授权 facts，组装 ProposalContext，使用共用执行/期限。并发前提应在成功恢复后的快照再复核，防止 load 与 begin_followup 间状态变化。 |
| [P/main.py:379](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:379) / get_proposal | api.py / route → Service.get_proposal → Repo.load_proposal | 重构拆分 | API 做状态码/响应，应用入口留策略；仓储只读不借私有字段旁路。 |
| [P/main.py:390](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:390) / mark_proposal_draft | api.py / route → Service.mark_draft_created → Repo.mark_draft_created | 重构拆分 | 机器可信的 Java 关联回写，API 解析正数 ID，仓储保留幂等锁；不得由普通模型参数直接调用。 |
| [P/main.py:189](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py:189) / ready | api.py / ready → Service.check_ready → Repository.check_ready | 重构拆分 | 协议投影与存储诊断分离，不穿透私有实现。 |

Repository 逐方法评估：

| 当前方法（SqlAlchemyInvestigationRepository） | 建议 | 责任及依赖理由 |
|---|---|---|
| [P/workspace/repository.py:80](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:80) / __init__、_connection | 保留在 repository.py | Engine/事务/内存 SQLite 串行连接保护是基础设施；不会因此将并发业务状态锁当成 SQLite 专用锁。 |
| [P/workspace/repository.py:104](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:104) / create_schema_for_tests | 保留并严格测试限定 | 只支持测试创建；生产仍用 Alembic。 |
| [P/workspace/repository.py:109](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:109) / check_ready | 原位修复 F11 | 仓储最了解支持的 schema，Bootstrap/API 组织调用，不把 SQL 搬进 health route。 |
| [P/workspace/repository.py:115](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:115) / recover_interrupted | 保留启动调用 | 单进程一 schema 下恢复遗留 RUNNING；不可当作分布式运行租约。 |
| [P/workspace/repository.py:125](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:125) / create | 保留 SQL 插入和返回快照 | UUID/时间可由 Session/Application 统一策略传入，但无必要单独造 IDFactory。 |
| [P/workspace/repository.py:145](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:145) / load | 保留 DTO 重建 | 读取多表/时间归一是仓储映射；不返回 ORM 实体给 Agent，也不在这里组 Prompt。 |
| [P/workspace/repository.py:277](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:277) / add_proposal | 保留 | Agent 记录 insert + 更新时间，业务授权/PII 不由 SQL 实现承接。 |
| [P/workspace/repository.py:299](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:299) / load_proposal | 保留 + V03 策略明确 | 当前按 Proposal.scope_version 读 Evidence，不是按 Investigation 最新范围；名称/注释应与策略一致。 |
| [P/workspace/repository.py:323](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:323) / mark_draft_created | 保留 | FOR UPDATE 后同 Draft ID 幂等，不同 ID 冲突；返回 load 的格式不变。 |
| [P/workspace/repository.py:340](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:340) / begin_followup | 保留 | 条件 UPDATE 防并发恢复；Service 处理成功后初始化失败的收敛，不削弱 CAS。 |
| [P/workspace/repository.py:370](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:370) / append_tool | 保留 | 行锁防轨迹 JSON 覆盖；不能因有 Python 列表就取消数据库并发保护。 |
| [P/workspace/repository.py:388](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:388) / add_evidence | 保留 + 范围条件 | insert 与更新时间同事务；补当前运行/范围不变量时不能无视采证起始版本。 |
| [P/workspace/repository.py:413](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:413) / add_hypothesis | 保留 | Hypothesis 保存，纯不变量由上层 Domain/Session 执行，外键/事务仍在这里。 |
| [P/workspace/repository.py:437](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:437) / update_hypothesis | 保留 | SQL 的 investigation_id/scope 条件防归属错误；顺序化不能替代持久化约束。 |
| [P/workspace/repository.py:464](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:464) / change_scope | 保留 | 版本 CAS + 旧假设淘汰原子。Proposal 策略若选择 supersede，持久化与内存必须同时对齐；已建 Draft 不应撤销。 |
| [P/workspace/repository.py:489](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:489) / add_message | 保留 | 可见消息保存，与框架原始 message history 明确分开。 |
| [P/workspace/repository.py:511](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:511) / finish | 保留并由统一 Service 调用 | 终态 CAS + 助手消息同事务，这是必要不变量，不能移到 API/Logger。 |
| [P/workspace/repository.py:41](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py:41) / InvestigationRepository Protocol；_utc | 保留 | 已有真实业务存储端口和驱动时间转换；不拆成 Evidence/Hypothesis/Message 五个只有一次调用的 Repository。 |

Python 其他基础方法：

- `AgentSettings.empty_optional_setting/is_test_model/validate_real_model` 保留配置文件；配置检查不是 LLM 内容检查。生产 DB URL 校验仍不等于实际数据库 GRANT 已隔离，需要部署核验。
- `RunAdmission.acquire/release` 保留 runtime；acquire 是单进程有锁的即时拒绝，release 是容量归还/安全时长事件，调用所有权要保证一次释放。总体取消/状态决定不塞进 Admission。
- `PulseFlowApiClient._request/_unavailable` 保留 Client；连接、凭证注入、固定错误和短时熔断高度相关。四个查询方法补响应对应规则；其他两方法保留类型/ID 约束。Client 不做 Hypothesis/Proposal 写库。
- `AzurePiiGuardrail` 四个重要方法仍属于安全策略/Provider 适配；新增官方模型钩子时复用 check，保留 fail-closed，不用“统一 validate”替代业务校验。
- `QualityMetrics.record_status/java_call/reference_check/java_contract_error/latency/snapshot`、`AgentLatencyProcessor.on_end`、`SafeSpanExporter.export/shutdown`、`Telemetry.__init__/agent_instrumentation/configure_endpoint/shutdown` 保留 observability。质量统计使用官方 Usage，语义 claim 质量仍需评测，当前 unsupported_claim_rate=None 是诚实的未知。
- `main.create_app` → `api.create_app`；`lifespan` → `bootstrap.lifespan`；`internal_auth` → `security/auth.py`。请求 DTO、HTTP 异常映射、GET/POST/PUT 简单路由及 frame/stream 整体在 api.py，不能因 SSE 中轮询数据库就把路由本体移到 Repository。

### F.3 Java 与前端重点方法映射

| 当前文件 / 类 / 方法 | 目标文件 / 类 / 方法 | 分类 | 所属理由及边界 |
|---|---|---|---|
| [JB/agenttools/AgentProposalGateway.java:62](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:62) / investigate | investigation/InvestigationController.investigate → InvestigationApplicationService.start | 重构拆分 | Controller 取得登录身份/协议；Service 协调准入、Agent start、owner 登记与失败补偿；HTTP 在 Client。 |
| [JB/agenttools/AgentProposalGateway.java:80](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:80) / get | InvestigationController.get → 应用查询 / Client | 重构拆分 | 保留跨服务前 owner 校验及公开响应过滤，不直接读 Agent DB。 |
| [JB/agenttools/AgentProposalGateway.java:87](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:87) / followUp | InvestigationController.followUp / ApplicationService.followUp | 重构拆分 | 问题/范围与业务准入属于请求用例；Client 只发 /resume 与安全状态映射。 |
| [JB/agenttools/AgentProposalGateway.java:103](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:103) / cancel | InvestigationController.cancel / ApplicationService.cancel | 重构拆分 | 保留 owner 校验，返回上游权威快照；Gateway 取消不能直接写 Python 运行表。 |
| [JB/agenttools/AgentProposalGateway.java:214](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:214) / propose | InvestigationController.propose / ApplicationService.propose | 重构拆分 | 会话身份和授权 facts 组装在 Java 用例；模型没有 owner 参数决定权。 |
| [JB/agenttools/AgentProposalGateway.java:110](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:110) / events、shutdown | investigation/InvestigationEventRelay.open/close | 重构拆分 | 线程池、可关闭 HTTP 流、有界 SSE 解析和公开事件投影同属流适配；Controller 返回 emitter。 |
| [JB/agenttools/AgentProposalGateway.java:173](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:173) / call、url、构造器 HTTP 配置 | investigation/AgentInvestigationClient.request/url/构造 | 原样迁移 + 契约修复 | 与 AgentProposalClient 同属 HTTP，但长请求/SSE 与短 Proposal 读取的超时不同，不为了复用把所有超时强制相同。 |
| [JB/agenttools/AgentProposalGateway.java:193](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:193) / publicView、removePrivate | 客户端/公开 DTO 投影协作（可留 Client 同文件） | 原样迁移/改类型投影 | 公开契约是 API 信任边界；不把无边界 JsonNode 清洗变成通用 SanitizerManager，也不依靠黑名单替代明确字段。 |
| [JB/agenttools/AgentProposalGateway.java:161](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java:161) / owned、validate | Controller/应用授权、DTO/配置检查 | 重构拆分 | owned 混登录/归属/配置；拆清规则但必须保留同一调用前置顺序。普通输入/配置不全归 Security。 |
| [JB/agenttools/AgentGatewayLimit.java:16](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentGatewayLimit.java:16) / acquire | 调查 Runtime 准入（可同 feature 保留） | 保留/迁包 | Java 用户级每分钟额度和 Python 全局运行名额目的不同，应同时保留；不能把 Java 短 start HTTP 槽当成整轮 Agent 并发数。 |
| [JB/agenttools/AgentInvestigationOwnership.java:22](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentInvestigationOwnership.java:22) / register | investigation/AgentInvestigationOwnership.register | 原样迁移 + 幂等/协调设计 | Java 业务归属写入，不是模型 Tool；失败补偿在应用用例处理。 |
| [JB/agenttools/AgentInvestigationOwnership.java:30](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentInvestigationOwnership.java:30) / assertOwner | 同类 assertOwner | 原样迁移 | 资源授权，每次入口保留；不能因有 token 而删除。 |
| [JB/agenttools/AgentInvestigationOwnership.java:40](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentInvestigationOwnership.java:40) / lockOwner | 同类 lockOwner | 原样迁移 | 当前 Java 写事务内归属锁，不能由 Controller 自行开事务/直接 JDBC。 |
| [JB/agenttools/AgentInvestigationOwnership.java:49](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentInvestigationOwnership.java:49) / validId | 同 feature 的 ID 契约 | 保留 | 路径/DB 标识结构检查不是完整授权；60 行内无需为它单建类。 |
| [JB/agenttools/AgentProposalClient.java:33](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalClient.java:33) / read | investigation/AgentProposalClient.read | 原样迁移 | 固定机器 HTTP、配置、超时和内容无关错误；DTO 解析失败不能回显原 JSON。 |
| [JB/agenttools/AgentProposalClient.java:49](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalClient.java:49) / markDraftCreated | 同类 markDraftCreated | 原样迁移 | 传输层只做回写；提交后重试/可观测策略由 ApplicationService 决定。 |
| [JB/agenttools/CampaignProposalDraftController.java:27](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftController.java:27) / create | Controller.create → ProposalDraftApplicationService.createDraft | 重构拆分 | Controller 只拿 operator、委托、映射结果，移出协调和吞回写异常。 |
| [JB/agenttools/CampaignProposalDraftService.java:44](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftService.java:44) / create | ProposalDraftApplicationService.createDraft + CampaignProposalDraftService.createVerified | 重构拆分 | 外层先读快照；独立 Bean 事务内锁、校验/复核、幂等、Java 写入；提交后关联。Java 最终验证不能由“verified”命名自动省略。 |
| [JB/agenttools/CampaignProposalDraftService.java:104](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftService.java:104) / find、ownedDraft | 事务 DraftService/查询方法 | 保留 | 已提交 Draft 优先返回，并再次做 Java owner 授权；已有 Draft 在 Agent 故障时也可用。RR 可见性需 V02 测试。 |
| [JB/agenttools/AgentToolController.java:24](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolController.java:24) / 六个接口 | 原 Controller | 保留 | 当前确实是 HTTP DTO → Service 的薄适配，不存在直接 Repository 调用；无必要再包一层 ToolFacade。 |
| [JB/agenttools/AgentToolService.java:43](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolService.java:43) / performance | 原 Service / performance | 保留 | 读取已有权威摘要、转换成安全聚合 DTO；不调用 compute 写库。 |
| [JB/agenttools/AgentToolService.java:70](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolService.java:70) / audience、toInstant | 原 Service / audience；时间口径共享 | 保留/合并复用 | 调 Java Validator/Preview、把 warning/errors 映成固定码，适合边界适配；业务判断不能迁到 Controller。 |
| [JB/agenttools/AgentMetricService.java:44](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:44) / query | 原 Service / query | 保留，可选 SQL 协作 | 请求约束、事实 → 指标/样本量/警告/API metadata 仍同一用例；Java 数学权威保留。 |
| [JB/agenttools/AgentMetricService.java:65](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:65) / breakdown | 原 Service / breakdown | 保留 | 正确委托统一查询，只有一次委托也有独立业务协议意义，不删除 Tool 入口。 |
| [JB/agenttools/AgentMetricService.java:74](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:74) / compare | 原 Service / compare | 保留 | 两期 bucket 对齐、delta/relative、零基准规则一起维护；不把计算转给 Python。 |
| [JB/agenttools/AgentMetricService.java:110](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:110) / attribution | 原 Service + 可选 AgentMetricReadRepository | 重构拆分（可选） | 聚合 SQL 读取与 DTO 转换可分离，仍禁止返回用户/原始事件。 |
| [JB/agenttools/AgentMetricService.java:135](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:135) / readCounts、bucket、attributionBucket、grouping、filterSql、parameters | AgentMetricReadRepository 对应方法 | 原样迁移（有条件） | 固定 SQL 模板、命名参数和 DB 数值映射属于读取实现；参数必须是已验证白名单，迁移后防注入不能丢。 |
| [JB/agenttools/AgentMetricService.java:218](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:218) / parseFilters、validateRange、dimension、rowLimit、requireMetric | 原 MetricService/相邻契约规则 | 保留 | 业务查询边界，不是 PII；Java 接口独立于 Python 使用，必须继续验证。 |
| [JB/agenttools/AgentMetricService.java:314](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java:314) / value、sampleSize、zeroDenominatorWarning；metadata | 原 Service；metadata 可同 AgentToolDtos 文件集中 | 保留/合并复用 | 指标公式/样本量/警告内聚；metadata 供两个 Tool Service 使用，不应因一个静态函数新建 Utility 类。 |
| [JC/draft/CampaignDraftService.java:63](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:63) / createDraft 两个重载 | 原 DraftService；内部 persistDraft 可收窄可见性 | 保留 + 边界修复 | 公共入口授权/校验/预估；接收预校验结果重载是可信内部协作，不能变成外部绕过 Java 最终校验入口。 |
| [JC/draft/CampaignDraftService.java:117](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:117) / updateDraft | 原 DraftService.updateDraft | 保留 + 锁/CAS | 编辑和状态迁移是业务，不上移 Controller；更新 DSL 时清理/刷新不再匹配的旧人数快照。 |
| [JC/draft/CampaignDraftService.java:153](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:153) / refreshPreview | 原 DraftService.refreshPreview | 保留 + 明确失败结果 | 调用权威 preview、保存 snapshot/version，不复制已存值；未知人数不能保存为无警告真实0，原始异常不进 API。 |
| [JC/draft/CampaignDraftService.java:203](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:203) / confirmAndCreate | 原 DraftService.confirmAndCreate | 保留 + 事务修复 | Java 最终授权/复核、Campaign/Rules/Draft 同事务、并发最多一次；人工确认绝不能迁到 Python Tool。 |
| [JC/draft/CampaignDraftService.java:278](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:278) / loadDraft、requireDraftOwner、pickStatus、isExpired、markExpired | 原 DraftService | 保留 | 都是草稿生命周期的高相关方法；修复 owner=null 和事务回滚标记，不能为了行数拆为多个 Manager。 |
| [JC/draft/CampaignDraftService.java:342](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java:342) / pickDailyLimit、pickWeeklyLimit、buildDescription、buildMessageTemplate | 原 DraftService/既有 Campaign 业务转换模块 | 原位修复/按需要合并 | 是 DSL → 实际 Campaign 的业务映射，必须兑现窗口/文案事实；不归 Security/HTTP Client。 |
| [JC/draft/CampaignDraftController.java:35](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftController.java:35) / create/get/update/refreshPreview/confirm、operatorId、toResponse | 原 Controller | 保留 | 当前整体以服务委托、会话身份、响应 DTO 为主；可继续同类集中，不直接 Mapper，改动集中在服务事务。 |
| [JC/validation/CampaignDslValidator.java:67](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignDslValidator.java:67) / validate、validateAudience/Condition/Value/Schedule/FrequencyCap | 原 Validator | 保留 + F15–F17 | 相同 DSL 能力的一组业务规则，388 行含大量说明；无必要按每字段拆校验器。 |
| [JC/validation/CampaignFieldRegistry.java:32](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignFieldRegistry.java:32) / init、get 与描述 DTO | 原 Registry | 保留 + Profile 契约对齐 | 多处 Validator/Converter/Preview 使用的真实注册表，合理；只登记可实际执行/生成的字段，不造抽象工厂。 |
| [JC/validation/DslToRuleConverter.java:49](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/DslToRuleConverter.java:49) / convert、toBoolean、toThreshold | 原 Converter | 保留 + 语义/精度修复 | Java DSL 编译为规则的明确业务转换；未知字段/来源不能静默 skip，不能用0掩盖转换失败。 |
| [JC/preview/SqlAudiencePreviewService.java:62](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/SqlAudiencePreviewService.java:62) / preview/evaluateConditions/evalOne/compare/toDouble/emptyResult | 原 PreviewService 实现 + Profile 最新值能力 | 合并复用 + 修复 | 候选集成本控制与人群业务求值同能力，可同类；统一最新标签/缺失/精度，完整 PII 不由这里实现。 |
| [JC/decision/DecisionEngine.java:59](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/decision/DecisionEngine.java:59) / evaluate、evaluateBatch | 原 DecisionEngine | 保留 | 事件/定时模式的业务决策用例，基础设施异常外抛，创建任务不直接发送。 |
| [JC/decision/DecisionEngine.java:255](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/decision/DecisionEngine.java:255) / evaluateRule、compare、loadRules、inActiveWindow、isBusinessException | 原 DecisionEngine 或同 feature 的规则求值协作 | 原位修复为主 | 不匹配、配置非法、临时依赖失败要区分；共享 Profile 查询保留。新增抽象 RuleEngine/RuleFactory 无真实必要。 |
| [JE/service/EventService.java:44](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventService.java:44) / acceptEvent、buildEventPayload、sendToKafka | 原 EventService | 保留 | 接入时间规范化和等待 broker ack 围绕同一能力，HTTP Controller 已薄。事件类型/价格/长度校验是事件契约，不整体迁 Security。 |
| [JE/service/EventPersistenceService.java:61](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java:61) / persist、upsertMetricHourlyAtomic、buildContextFromEvent、buildUserEvent | 原事务服务 | 保留 + 回滚修复 | 数据库事实与小时桶原子、重复读 canonical 必须保留；JSON/时间映射组内内聚，可复用 JsonUtil 而不新造 Mapper 层。 |
| [JE/consumer/EventConsumer.java:61](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java:61) / consume、executePhase2/3、writeCompensationTask | Consumer.consume → EventProcessingService.process/processCanonical | 重构拆分/合并复用 | Kafka 适配与可重放业务阶段分开；正常/补偿复用画像/决策/点击/归因，不重复维护流程。 |
| [JC/profile/RealtimeProfileUpdateService.java:94](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java:94) / update、Lua、parseEventTime/辅助方法 | JP/service/RealtimeProfileUpdateService 同方法 | 原样迁移 + 时间/TTL修复 | 纯画像写入所有权迁移，Event/Job 复用不变；不再由 Campaign 包拥有。 |
| [JP/service/ProfileService.java:37](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/ProfileService.java:37) / getRealtimeMetrics | 原 ProfileService | 保留 | Redis 实时读取；字段注册与读取来源对齐，不把原始 cart 内容送模型。 |
| [JP/service/ProfileService.java:67](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/ProfileService.java:67) / getWindowMetrics、getMetricValue | 原 ProfileService / 明确类型与缺失的读取 | 保留 + 精度/失败契约 | 缓存/最新持久化回退属于画像读取；金额不能一律 longValue，Redis 故障与无值要可区分。Web 可容忍回退不等于决策可静默成功。 |
| [JP/service/ProfileService.java:103](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/ProfileService.java:103) / getUserTags、hasTag | 原 ProfileService / 最新标签能力 | 合并复用 | hasTag 先取最新再解释0/1的设计保留，Preview/Web 不再各自“先筛1”。 |
| [JP/service/UserPreferenceService.java:45](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/UserPreferenceService.java:45) / canDeliver/isDnd/isUnsubscribed/isAlreadyConverted/写开关 | 原 Service | 保留 | 业务触达偏好，不是操作员权限；实际发送有频控，偏好预判时点需明确，不以“前面查过”删除关键发送边界保护。 |
| [JC/delivery/DeliveryService.java:38](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryService.java:38) / createDeliveryTask、publishToKafka | 原 Service/既有补发契约 | 保留 + 事务/发布验证 | dedup 唯一任务 + dispatch 状态 + Job 重发有价值；发布与提交的真实 InnoDB 行锁/消费语义要集成测，不能仅见 pre-commit 就断言已发生丢消息。 |
| [JC/delivery/DeliveryConsumer.java:43](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryConsumer.java:43) / consume、sendViaChannel、sendInApp/Push/Email、upsertDeliveryRecord | 原 Consumer 的受控交付能力 | 保留为主 | 领取→额度→渠道→状态是一条能力，可先集中；模拟渠道不能宣称真实 SMTP/Push exactly-once。新增真实渠道/独立重试入口时才提取协作，不预造 Sender 工厂。 |
| [JC/delivery/FrequencyControlService.java:73](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/FrequencyControlService.java:73) / check | 原 Service | 保留 + 失败分类 | 发送期 Lua 原子判断/预留和 retry reservation，真实业务保护；不能移到 Controller 前检替代发送检查。 |
| [JC/delay/DelayedTaskManager.java:30](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delay/DelayedTaskManager.java:30) / addDelayedTask/claimTasks/completeTask/recoverStuckTasks | 原 Manager | 保留 | 有 Executor/Decision/Recovery 三方真实调用，Lua 原子操作有必要；不是仅因 Manager 命名就删除。 |
| [JC/delay/DelayedTaskExecutor.java:57](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delay/DelayedTaskExecutor.java:57) / executeDue、processOne、isCartItemPresent | 原 Executor | 保留 | 领取并复核购物车/偏好，再建幂等任务；故障保留 processing 的语义必须有恢复。与 Attribution 键/持久化规则不同，不强行改同 namespace。 |
| [JC/attribution/AttributionService.java:153](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java:153) / onTargetEvent、recoverOrphanedPendingTask | 原 AttributionService，经完整事件阶段调用 | 保留 + 耐久接入 | DB 任务创建与重复恢复属于归因；注册 after-commit Redis 的设计保留。EventConsumer 不能吞掉 DB 尚未建任务的失败。 |
| [JC/attribution/AttributionService.java:217](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java:217) / claimExpiredTasks、completeClaimedTask、requeueClaimedTask、ensurePendingTask | 原归因队列相关方法 | 保留 + 租约恢复 | pending/processing 原子迁移同能力，新增自身超时回收；不能依赖扫描不同键的 DelayTaskRecoveryJob。 |
| [JC/attribution/AttributionService.java:276](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java:276) / executeAttribution、markTaskMatched/Expired、requireTaskUpdate | 原 AttributionService | 保留 + 候选修复 | 最近有效触点、结果写入/目标事件幂等/任务终态同事务。Sql/Redis 的机械重复不是删除业务状态保护的理由。 |
| [JC/attribution/AttributionService.java:369](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java:369) / reconcilePendingTasks、schedulePendingTaskAfterCommit | 原 Service/消费者调度 | 保留 + 完整性修复 | 修复已提交 DB → Redis 窗口有价值，但不覆盖未创建的 DB 任务或永久 processing；要有公平遍历/超时回收测试。 |
| [JC/attribution/ClickEventService.java:24](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java:24) / recordClick | 保留并由可信 canonical CLICK 流程调用 | 接通 + 幂等修复 | 当前缺调用是业务断链，不能当纯死代码删；需验证任务/用户和来源去重，不直接接受模型写入。 |
| [JJ/handler/CampaignSelectionJob.java:37](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignSelectionJob.java:37) / execute、createExecutionInstance、executeCampaign、recoverStuckExecutions、computeNextTriggerAt | JC/scheduling/CampaignExecutionService 的调度/执行方法 | 重构拆分 | Job 留 XXL 入口和选择/委托；原子领取/实例/ONCE/重试属于 Campaign 用例。事务必须通过实际代理或 TransactionTemplate。 |
| [JJ/handler/WindowMetricJob.java:71](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/WindowMetricJob.java:71) / processUser、computeAndSave、三个 sum 方法 | JP/service/WindowMetricService | 原样迁移 + 业务定义修复 | 画像生成属于 Profile，不把 Job 的调度职责扩大为字段定义权威；调用方和验证 oracle 一起对齐“天”与“次”。 |
| [JJ/handler/TagRecalcJob.java:30](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/TagRecalcJob.java:30) / execute 的用户选择/策略/保存 | JP/service/TagRecalculationService.recalculate | 重构拆分 | 保留八种实际 TagStrategy，补不活跃用户覆盖及窗口语义；Handler 委托业务能力。 |
| [JJ/handler/CompensationJob.java:58](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CompensationJob.java:58) / execute、markDone、handleFailure、parseEvent | 补偿执行协调 + EventProcessingService.replayCanonical | 合并复用 | 领取/重试状态可在 Job service；重放业务阶段复用 Event 应用流程，不复制/遗漏归因。 |
| [JJ/service/CompensationClaimService.java:38](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/service/CompensationClaimService.java:38) / claimOne | 原 Service | 保留 | 独立 Bean 的 FOR UPDATE SKIP LOCKED + markProcessing 真事务边界，即使只有一次调用也有明确价值。 |
| [JJ/handler/RetryCompensationJob.java:28](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/RetryCompensationJob.java:28) / execute | JC/delivery/DeliveryRecoveryService + 补偿状态协调 | 重构拆分 | Delivery 与 Event replay 有不同状态/次数契约；先修 NULL next_retry_at，避免万能 CompensationManager。 |
| [JJ/handler/DispatchRetryJob.java:31](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DispatchRetryJob.java:31) / execute | 保留 Handler，复用 Delivery 发布方法 | 合并复用 | DB Pending 扫描与重发真实需要；避免与 DeliveryService 各自定义序列化/Topic/超时/状态更新规则。 |
| [JJ/handler/DelayTaskRecoveryJob.java:22](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DelayTaskRecoveryJob.java:22) / execute | 原 Handler | 保留 | 薄委托有实际调度意义；归因需自己的恢复，不能仅改任务字符串共用。 |
| [JJ/handler/DailyMetricJob.java:20](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DailyMetricJob.java:20) / execute；DataCleanupJob.execute | 原 Handler（明确 Profile/保留策略） | 保留为主 | 小型单 SQL 和受控清理无需再包单函数 Service；大规模清理时再按真实锁/保留策略改批次，不能按 LOC 认坏设计。 |
| [JC/analytics/PerformanceSummaryCalculator.java:66](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java:66) / compute、computeHistoricalBaseline、rate | analytics/CampaignPerformanceSummaryService.refreshOrLoad、baseline、rate | 重构/命名调整 | 原类实际持久化/封存，名称反映副作用；保留同能力集中、公式一致，Tool 只读适配不调用写路径。 |
| [JB/web/WebQueryService.java:83](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:83) / dashboardSummary/dashboardTrends/hourlyTrend/dailyTrend/queryBuckets/count/systemStatus/health | web/DashboardQueryService；标准健康适配 | 重构拆分 | 汇总/趋势与可用性投影；不再将错误变0或假UP，数学定义从业务分析复用。 |
| [JB/web/WebQueryService.java:133](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:133) / listCampaigns/campaignDetail/Performance/Trend/Deliveries/Attributions、PerformanceNumbers/映射 | web/CampaignQueryService | 重构拆分/合并复用 | Campaign 读模型批量事实，统一摘要/回退口径；campaignDetail 的 operatorId 当前未使用，普通全局只读策略需 V03 核验后再决定删除或真正应用。 |
| [JB/web/WebQueryService.java:230](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:230) / listUsers/userDetail/userProfile/windowMetrics/realtimeMetrics/activeTags | web/UserQueryService + Profile 的明确读取能力 | 重构拆分/合并复用 | 用户读模型/DTO 映射留同组；最新值/标签规则不复制；可容忍回退要有来源字段。 |
| [JB/web/WebQueryService.java:278](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java:278) / listEvents/event/listDeliveries/delivery/listAttributions/attribution 和各视图映射 | web/ActivityQueryService | 重构拆分 | 观察型读模型，保留已登录准入；mapping 内 I/O 改批量，普通格式解析助手留组内，不立几十个 Utility。 |
| [JB/config/DevLoginController.java:45](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/DevLoginController.java:45) / login/loginGet | web/DevLoginController | 原样迁移 | Controller 不属于配置包；仅显式开发开关启用。默认关闭是用途明确的开发能力，不能只因关闭就认死代码。 |
| [JB/config/AgentTelemetryConfig.java:29](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/AgentTelemetryConfig.java:29) / privacyCustomizer + SafeSpanExporter | 原配置及其 Observability 策略 | 保留 | 官方 OTel 装配与白名单导出，跨语言实现是不同运行时边界，不强行抽公共 Python/Java sanitizer。 |
| [W/views/Investigations.vue:40](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Investigations.vue:40) / refresh/load/connect/stopStream | composables/useInvestigationSession.ts 对应方法 | 重构拆分 | 请求版本、路由身份、SSE 和取消有同一生命周期，集中所有异步回写门。 |
| [W/views/Investigations.vue:89](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Investigations.vue:89) / submit/cancel/improve | useInvestigationSession + UI 的文案动作 | 重构拆分 | 网络/运行状态归会话；表单提问/文案是展示意图，不让 composable 决定业务授权。 |
| [W/views/Investigations.vue:117](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Investigations.vue:117) / propose/generateDraft/review/rePreview/confirm | composables/useDraftReview.ts + 视图人工确认 | 重构拆分 | 网络结果按原调查/草稿身份接纳；ElMessageBox 用户二次确认仍在明确 UI 边界，后端依然校验。 |
| [W/api/investigations.ts:57](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/investigations.ts:57) / watchInvestigation、业务 HTTP 函数 | 原 API 模块 | 保留 | 协议/SSE读取与业务请求适配，不持有页面 refs；disconnect 不自动伪造服务端取消。 |
| [W/api/http.ts:15](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/http.ts:15) / 拦截器、unwrapApiResponse、apiRequest、setToken | 原 http.ts | 保留 | 统一传输/错误与明确 demo 分支；不塞入视图业务状态/资源权限。 |
| [W/stores/auth.ts:9](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/stores/auth.ts:9) / login/restore/logout | 原 Store | 保留 | 会话恢复/清理围绕同能力；真实授权仍 Java；需要跨标签协同时再增加方案，不预造 IdentityManager。 |
| [W/router/index.ts:31](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/router/index.ts:31) / beforeEach | 原 Router | 保留 | 页面准入与导航，不代替 API 授权。 |
| [W/views/CampaignDetail.vue:21](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/CampaignDetail.vue:21) / load；UserDetail.load；其他列表/抽屉 load | 原视图 + 局部请求身份/参数 watch | 原位修复 | 路由参数变化和旧请求覆盖；普通页面无需统一搬到全局 Store。 |
| [W/components/TrendChart.vue:24](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/components/TrendChart.vue:24) / render/resize/watch/unmount | 原组件 | 保留 | ECharts 创建、更新、尺寸及销毁同组件资源生命周期；无需再拆 ChartManager。 |
| [testing/functional/validate.py:1384](C:/workbuddy-workspace/PulseFlow/testing/functional/validate.py:1384) / write_summary/main | 原 Validator | 原位修复 | 证据准入先于判 PASS；报告格式助手可同模块，不能因 1618 行就把每项 SQL 拆文件。 |
| testing generate/replay/state/performance/run-all | 原有模块 | 保留 | 数据生成、真实接入、隔离状态、最终事实、性能和总编排是可解释分工；小型重复 JSON 格式化不足以引入自研测试框架。 |

### F.4 删除候选及真实调用证据

引用核查范围为全部当前 main 源码、tests、Web、testing、配置/迁移，排除了 node_modules/.venv/target。用 rg/AST 定位后读实现与调用，不将 Spring 注入入口当作无调用；以下是**仓库内证据**，删除前仍检查库外调用/交付约定。

| 候选 | 实际引用/实现证据 | 建议 |
|---|---|---|
| AudienceMetricsAggregator / AudienceMetrics | 生产文本引用仅聚合器自身和结果类型；无 Controller/Service/Job 注入或 aggregate 调用。实现重复 Preview 圈人，含 active-no-spend 冒充 cartWithoutPurchase 等旧近似。 | 无计划恢复人群洞察时一起删除；若恢复，复用真实匹配/画像，不长期维护第二套条件语义。不能声称它当前是被 API 使用的权威计算器。 |
| AudiencePreviewService.previewUserIds / SqlAudiencePreviewService.previewUserIds | 只有接口默认方法和实现，无调用；实现先执行人数预估，再无论人数如何都 return emptyList。 | 删除占位能力，不把恒空当“已有用户圈选 API”。 |
| SqlAudiencePreviewService.converter 注入 | 构造字段声明/赋值，无任何方法读取；实际求值使用 FieldRegistry，未调用 converter。 | 去掉无效依赖，更新测试构造；不为保留构造签名继续传一个无用对象。 |
| CampaignContentValidator / ContentResult / ContentVariant | 实际 validate 调用仅 CampaignContentValidatorTest；当前生产 Agent 工具与 Draft 流程不调用，测试存在不代表线上防线存在。 | 删除/明确为非运行库二选一；若未来上真实文案能力，按事实授权和 PII 新边界接入，不能宣称现有草稿文案已受它保护。 |
| DecisionResult / FrequencyCheckResult | 各类仅声明，决策/频控真实返回分别是 void/FrequencyControlService.FreqResult。 | 删除未使用的平行 DTO 候选。 |
| DevLoginResponse | DevLoginController 使用 Map 返回，该嵌套类仅声明。 | 删除嵌套闲置类型；Controller 的显式开发能力另行保留。 |
| OperatorContext / AgentDependencies.operator_context / run 同名参数 | AST/全文调用索引显示只构造/传递，无读取 operator_id/role 的生产方法；应用流程传默认 None。 | 删除候选；不得因此删除真正使用的 ProposalContext、owner 或 Java 归属。 |
| CampaignProposal.to_dsl | 只有定义，无客户端/工具/测试调用。Java 独立从保存方案构造 DSL。 | 删除候选或明确保留为库 API；不为了使用它新增不必要写入路径。 |
| MAX_NESTING_DEPTH、频控两个未用 TTL 常量、未用 import | 常量仅定义，实际 DSL 无嵌套结构；Lua 使用字面量 TTL。 | 随相关修复小范围清理/统一实际 TTL 来源，无需单独架构工程。 |
| max_cost_usd | 只配置声明/样例/文档，无运行读取，文档明确 reserved/not enforced。 | 保留诚实标注或移除预留配置；不是新增成本子系统的依据。 |
| ClickEventService.recordClick | 无生产调用，但 click_event 是真实指标/归因事实源，fixture 明示断链。 | **不建议直接删除**；按 F39 接通可信入口/幂等。 |
| DevLoginController、模拟器 | DevLogin 是条件注册的开发入口；simulator 是独立 Maven 模块而非 boot 默认依赖。 | 不因默认关闭或未打包机械删除；部署目的见 V01。 |
| 历史 ai_generation_record/campaign_ai_review 与 V1–V7/旧 Alembic | 当前部分表无运行 Java 类型，但迁移、历史数据和测试仍有依赖。 | **不建议直接删表或改历史迁移**。后续真实退役须单独数据盘点/增量迁移。 |

### F.5 用户列出的 13 个候选的复核结论

| 候选 | 源码复核后的结论 |
|---|---|
| 1. GrowthInvestigator 过多职责 | 有条件提取 Tool 适配；生命周期必须先归 Service。不是因长度或 Tool 数量直接认定整类不合理（F08/F03）。 |
| 2. Workspace 混领域/Prompt/DB/Trace | 已确认；实际属于每轮应用会话，整体迁移并移出渲染/取 Trace，不碎片化（F07）。 |
| 3. main lifespan 抽离 | 建议 Bootstrap 组织和 API 分离，资源释放缺口已确认；底层实现不全部搬入 Bootstrap（F09）。 |
| 4. internal_auth 归安全 | 认证策略应迁 security，API 注册；当前 fail-closed 与固定错误保留，不是删除安全能力的重构。 |
| 5. Service 读 _settings | 已确认，显式注入 RunAdmission/settings（F06）。 |
| 6. 路由读 _repository | ready/get_proposal/mark_proposal_draft 三处已确认（F06）；普通调查路由多已走 Service。 |
| 7. AgentDependencies 无效依赖 | operator_context 确认只传不读；其余有真实用途，proposal_created 是每轮能力状态，不是全局幂等保证（F36）。 |
| 8. AgentProposalGateway 混杂 | 已确认 HTTP/MVC/SSE/投影/生命周期，按真实方向提取（F24）。 |
| 9. ProposalDraftController 协调 | 已确认提交后回写协调，但当前“提交后失败不回滚 Draft”语义正确，应迁应用层而不改变（F25）。 |
| 10. AgentMetricService 混杂 | 固定聚合能力有内聚性与防注入价值；SQL 提取有条件，不把参数验证全部搬 Security（F26）。 |
| 11. 运行/Diagnosis 状态混用 | DTO 概念已有区分，问题在 Service 日志/统计和超时原因投影；不强行统一 wire enum（F10/D.2）。 |
| 12. 安全散落 | 策略主要集中 AzurePiiGuardrail/Java 业务验证，边界主动调用合理；真正缺口为实际模型重试/完整内容/普通日志，而非调用点分散本身（F01/F02/F33）。 |
| 13. 未发现同类问题 | 全仓还确认 Java 事务/恢复/DSL/画像/前端及验收器问题；详见 F13–F42，并非局限提示中的类。 |

## G. 重构风险、实证与验证方案

### G.1 各边界的风险与必须验证的行为

| 边界 | 迁移/修复风险 | 必须保持或显式改变 | 回归要求 |
|---|---|---|---|
| Pydantic AI Tool Schema | 提取闭包、通用包装、改参数名/注解会改变模型看到的工具 | 保持 11 个 Tool 的公开名、request 包装、字段 aliases、docstring、返回形状与 prepare；不自建 schema | 用 FunctionModel 的 AgentInfo 和真实工具定义冻结前后 Schema；可选 Proposal 工具在授权/证据/已生成不同状态正确显隐 |
| 动态调用、并发与 Retry | 全串行会降低独立查询能力；只保护 Proposal 仍会 scope race；Schema Retry 早于函数 | 使用官方顺序分组/版本核验、官方 ModelRetry/UsageLimits，新增实际消息保护 | 同问题不同事实走不同路径；一个响应并行 query+scope、两次写同假设、无授权 Tool/未知 Tool/Schema Retry 均覆盖 |
| PII Guardrail | 移动调用点可能让数据先存库/先发 Java/先回模型；全面扫描可能增加 Azure 耗时/误报 | 本地先检查、真实模式 Azure、服务故障 fail-closed、loggingOptOut、固定错误；完整内容和实际请求检查 | 首请求/后续请求/重试/工具参数/观察/假设/Proposal 各字符串/最终输出；检测器超时、畸形响应和取消，确认无下一模型请求/无敏感持久化 |
| Evidence/Hypothesis/Diagnosis | Scope 标签、引用集合、支持/反对互斥、历史上下文裁剪变化 | 保留 UUID、java_query_id、data_version、scope_version 和历史记录；修复无引用 Finding | 当前/历史/跨调查/虚构引用、SUPPORTED/WEAKENED 门槛、终态与消息原子性、重载后一致性 |
| Proposal → Draft | 事务拆分/锁内重查/回写时点改变，可能放开 owner 或重复写 | Java owner+status+Evidence+DSL+事实仍最终验证；已有 Draft 优先；提交后修复 | 两人交叉读/写/流；并发同 Proposal；Agent 读/回写故障；Java commit 后响应丢失；不同 Draft ID 回写冲突 |
| API 与 SSE | main/API/Controller 迁移、DTO aliases、advice 类型/过滤器路径改变 | 路径、状态码/固定错误、snake/camel 现有协议保留；SSE 仍业务事件 | 旧 API 契约、认证先于非法 JSON、所有调查 owner、断连/满连接/上游卡住/超长帧/终态、Web 重连后 GET 权威 |
| Java 可执行 DSL | 拒绝静默近似会使过去“valid”的请求被拒；实现 OR/Decimal 也可能扩大能力 | 明确选择“拒绝不支持”或“全链路支持”；不能当纯文件迁移隐藏行为变化 | validate → preview → convert → DecisionEngine 结果等价：OR、否定标签、金额小数、缺值、实时时段、频控窗口、ONCE、优惠范围/有效期 |
| MySQL 事务/兼容性 | self-invocation、隔离级别、回滚标记、锁顺序、幂等键语义 | 原表/列/迁移不因迁包改变；事件+桶、确认3表、Proposal Draft 链接事务保持 | 真实 MySQL故障注入：事件 insert 后金额解析/桶失败、Draft 双确认/编辑竞态、调度推进后实例失败、RR 并发重查；显式检查 commit 后结果 |
| 并发、重试、取消、关闭 | 被 shield 的清理可能继续使用已关闭 Engine；部分运行不受 _tasks 管理；重复释放 | 一进程一 Agent schema；每轮唯一受管 owner；有界清理；持久化状态是权威 | before task start、工厂/消息/Tool/最终 PII/finish 各阶段取消，客户端断连、SIGTERM、存储超时、重启恢复，容量无泄漏 |
| Kafka/Redis/后台恢复 | ACK 早于耐久阶段、NULL retry time、processing 永久孤立、字段/时钟漂移 | canonical 事件、业务 dedup、原子 Lua/领取、归因 after-commit、Task reservation 保留 | DB/Redis单独故障、Phase2重放补建归因、claim后进程崩溃、queue cleanup失败、首次 Delivery PROCESSING 崩溃、ONCE 与重试上限 |
| 日志与 Trace | 调试新增 raw exception/Prompt/SQL，SafeSpanExporter 之外仍泄漏 | 官方 include_content=False、无头采集、白名单导出、固定操作码 | Fake exporter + 日志捕获，sentinel 在属性/事件/status/resource/SSE/HTTP/数据库 warning 中均不出现；保留跨服务 traceparent |
| 前端与验收器 | 路由切换旧响应、只检查 DOM 不证明业务、无证据 PASS | API 层与 UI 状态分离、显式二次确认、demo 无真实证据、不对未知补0 | 延迟响应跨路由/卸载、草稿状态门、断流手动刷新；缺 Replay/损坏报告/0断言必须 NOT_RUN/FAIL；业务 oracle 不只复制实现 |

纯职责迁移不需要修改 MySQL 表结构。**已识别的例外是接通 CLICK 时的来源幂等**：目前 click_event 没有 source_event_id 唯一约束，如果现有结构不能可靠防重复，应提出独立 V8 等增量迁移并验证历史数据兼容，不能改 V1 或用一次内存布尔值冒充跨服务幂等。新增运行 wire 状态、请求去重身份或任意小时滚动频控存储也是显式协议/数据设计事项，不能混进“仅搬文件”。

### G.2 本轮离线探针的可复现证据

探针使用当前源码中的真实 Service/Agent/Workspace/Repository/Validator/Converter；模型为 FunctionModel 或 TestModel，HTTP 为 MockTransport，数据库为内存 SQLite，Java 为已编译当前类的本地调用。测试输入为合成业务标记，没有把真实用户数据或密钥作为探针输入。临时探针只存在内存/系统临时目录，仓库未新增测试源码。

| 探针 | 操作方式 | 观测结果 | 对应结论 |
|---|---|---|---|
| S01：范围初始化失败 | 真实 Repository 先完成一次调查；start 恢复，令 change_scope 在 RUNNING 已提交后失败 | `status=RUNNING, active=0, owned_tasks=0` | F03；现有后台异常兜底在创建 Task 之后，覆盖不到这里 |
| S02：最终 PII 超时 | FunctionModel 立即返回有效诊断；Guard 在 final summary 检查阶段等待；Service timeout=0.5s | `entered_final_guard=true, status=RUNNING, active=0, owned_tasks=0` | F03；CancelledError 不属于 except Exception；现有测试只取消 agent.run 内的等待 |
| S03：Finding 无引用 | Workspace 中已有真实生成的 Evidence ID；顶层引用它，Finding 引用列表为空 | `accepted=DIAGNOSED, finding_reference_count=0` | F05；空集合子集校验不足 |
| S04：并行范围/采证 | 同一模型响应发 query_metric 和 update_scope；查询在旧版本开始，等待范围切换后返回 | `query_start_scope=[0], evidence_scope=1, final_scope=1` | F04；使用的是实际 Pydantic AI 并行 Tool 调度 |
| S05：方案检查投影遗漏 | 合法 CampaignProposal 结构的 timezone 中放合成禁字段；分别 check(free_text()) / check(完整模型) | `free_text_passes=true, whole_proposal_blocked=true` | F02；是字段覆盖证据，不依赖 Azure 对自然语言的检出率 |
| S06：Schema Retry 消息 | 模型发 query_metric，time_range 为合成禁字段字符串；第二次模型读取 RetryPromptPart.content | `model_calls=2, raw_blocked_field_reentered_model=true` | F01；Tool 函数尚未执行，业务回调的 guard 没有机会阻止 |
| J01：OR/平铺规则 | Java FieldRegistry、Validator、Converter；两条件 OR | `or_accepted=true, flat_rules=2`；DecisionEngine 源码逐条 AND | F15；结构化校验与编译后执行语义不同 |
| J02：小数阈值 | spend30d GTE 9.9 转换 | `{"metricType":"spend_30d","operator":"GTE","threshold":9}` | F15；数值精度已在转换阶段丢失 |
| J03：优惠/小时窗口 | discount=-1、正数 2h cap 的完整 DSL | `negative_promotion_and_two_hour_cap_accepted=true` | F17；F16 的执行映射另以源码核对，不能把“接受2h”单独当作漏洞 |
| J04：整数条件 | INTEGER 条件值为 Number 1.5 | `fractional_integer_accepted=true` | F15；不是只有 JSON declared valueType 的结构问题 |
| T01：验收缺证据 | Validator.write_summary，checks=[]、failures=[]、无依赖未运行标记；对应 main http-only 无 Replay 路径 | `exit_code=0, status=PASS, check_count=0` | F41；没有断言/执行证据仍被判通过 |

建议将以上探针转为仓库中的长期回归，**不是本轮已新增测试**。针对 MySQL 的事务/并发不应仅用内存 mock 代替；针对模型能力不应将脚本化 FunctionModel 结果当作真实推理质量。

### G.3 现有测试审查：保证与盲区

| 测试/组 | 实际验证的保证 | 当前盲区/后续必要用例 |
|---|---|---|
| Python foundation、azure_privacy、safety | 本地禁字段先于 Azure/模型、Azure 故障拒绝、loggingOptOut、虚构 Evidence 拒绝、恶意 Java 维度不进下一模型请求 | Schema Retry 内容、完整 Proposal 字符串、最终 PII 取消、入口 .env 隔离 |
| trajectories、remaining_tools、22 case eval | 同问题可不同工具路径，六类 Tool、固定错误、Evidence/Usage/control-flow；真实 Provider 显式 opt-in | OfflinePolicy 读取 expected_status 决定输出，仅证明控制流；current_scope_only 等声明并非全部有独立 oracle；不证明真实模型因果/数字声明质量 |
| workspace/repository/followup/mysql | 记录重载、范围历史化、假设/消息保存、恢复 CAS、模型阶段取消、MySQL基础往返 | 原子写后失败、并行范围、同时假设更新、scope初始化/最终Guard/finish取消；本轮 MySQL 用例未运行 |
| proposals/migration | 保存方案不写 Java，不能发明授权 facts；历史无 owner Proposal 迁为 CANCELLED 并保留 Draft 链接 | 重复请求/提交响应未知、范围变化后的方案政策、完整 PII；Java owner=null Draft 未被这条 Python 迁移保护 |
| runtime/tracing | 无排队准入、分钟限制、后台模型取消/超时/关闭、启动恢复、Client熔断、HTTP/API/SSE与官方Trace | 同步运行登记、最终检查取消、ready缺子表/新列、模型 Provider资源关闭、入口PII统计/PII span Provider |
| Java AgentGatewaySecurity/ProposalGateway/Auth/ToolService/MetricService | owner先于上游、机器 token、公开字段过滤、SSE固定事件、聚合SQL绑定参数/公式、只读摘要/Preview错误码 | owner登记失败后Agent已启动；上游阻塞/超长SSE读取；实际跨服务Draft链路；时间窗对应与不支持字段 |
| Java CampaignProposalDraftServiceTest | owner/status/evidence/DSL再验证、已有Draft优先、重复请求/回写失败的提交后语义 | Mapper/validator/preview 都是 mock；不证明 RR 下锁内重查、真实事务/唯一键竞争或端到端优惠/频控语义 |
| Java CampaignDraftServiceTest | 状态门、过期拒绝、顺序重复确认、创建DRAFT Campaign | 直接 new Service + 内存Map 保存同对象；没有真实代理事务、快照隔离或并发确认/编辑测试，不能证明 F13 已受保护 |
| Java CampaignDslValidator/Preview/Decision 测试 | 基础字段、operator/type/范围、布尔 true、简单规则/去重；Preview 单个标签正例 | OR编译、Decimal阈值、INTEGER小数、false/新0标签、字段来源、优惠/小时窗、未知规则、Profile依赖故障 |
| Java EventPersistence/Consumer/IT | canonical重复、NULL与0、时间格式、原子upsert基本往返 | insert成功后桶/金额解析失败是否回滚；Consumer测试主要解析；Phase2失败归因/补偿完整性未覆盖 |
| Java AttributionService/Consumer | after-commit、rollback不调度、DB→Redis恢复、异常重排、终态幂等和cleanup失败 | claim后硬崩溃的永久processing、未建DB任务、最新非法点击遮挡较早有效点击、公平批次遍历 |
| Java Job/Delivery/Profile | 当前部分能力经其他测试或fixture间接使用 | 无对应 Job/DeliveryConsumer/FrequencyControl/ProfileService 的完整直接测试；NULL retry time、ONCE/调度原子性、乱序/TTL、画像字段定义均需补 |
| Web 3 files/7 tests | HTTP envelope/格式、调查展示、单独生成Draft、确认对话框拒绝/通过 | 路由切换、卸载、异步竞争、NEEDS_CONFIRMATION/EXPIRED按钮、真实SSE帧读取、真实Java确认 |
| Web E2E/Compose smoke/testing | 已阅读；E2E主查页面导航；smoke离线调查/owner/PII；functional有存储断言与隔离清理 | 本轮未执行；E2E没有真实Proposal→Draft→confirm业务断言；smoke TestModel不查Java工具；fixture预写Click，不能证明点击入口 |
| Functional Validator/k6 | 最终存储与HTTP性能分别验收，NOT_RUN语义有明确设计 | 无Replay可PASS；画像 oracle复制当前错误计数口径；last_*只验存在；不能用k6 HTTP 200证明事件最终落库/归因 |

重构验证应覆盖“入口 → 调用 → 状态 → 持久化 → 响应”的业务结果；避免大量仅断言方法被调用或文件移动后的镜像测试。现有能验证真实不变量的用例继续保留和适配。

### G.4 需要进一步验证的四项事项

| 编号 / 分类 | 已知源码事实 | 未知与建议核验 | 本轮不作的结论 |
|---|---|---|---|
| V01 · 需要进一步验证：部署与入口 | Agent准入/恢复/取消单进程；Docker 1 worker；Java有本地默认口令/内存会话、DevLogin开关、不同系统时区使用；Compose是开发测试栈 | 实际副本数、每schema进程数、JVM/MySQL业务时区、数据库GRANT、生产是否替换本地身份、入口是否阻断/internal、Simulator是否另行打包 | 不声称当前生产已暴露默认口令/模拟器；不无条件上分布式限流/租约。若确需多副本，先设计租约和会话存储，不能只加worker。 |
| V02 · 需要进一步验证：MySQL隔离与发布 | Proposal Draft事务首次普通find在owner锁之前；锁内第二次是普通select；Delivery在事务内publish；唯一键/条件更新存在 | 用真实MySQL RR/RC并发请求验证第二次快照可见性、重复键响应、锁等待/提交/回滚与Kafka消费；记录锁顺序/超时 | 不把owner行锁+唯一键直接称作并发请求永远200；也不只见pre-commit publish就断言必丢消息（InnoDB current UPDATE可能等待锁）。 |
| V03 · 需要进一步验证：存量与业务政策 | Java Draft允许owner=NULL；Python旧Proposal迁CANCELLED；change_scope只淘汰假设，load_proposal按Proposal旧scope读引用；Web仅标历史仍可生成Draft；普通Campaign详情operatorId未使用 | 只读盘点ownerNULL/历史GENERATED/DRAFT关联；明确历史方案能否独立成Draft、普通运营只读是否全局；若历史方案不允许新建，DB+内存+Java+UI一起supersede，只影响未生成Draft的方案 | 不仅凭unused operatorId就判所有普通查询IDOR；不默默撤销已提交Draft或删除历史Evidence。 |
| V04 · 需要进一步验证：真实质量/遥测 | 原生Usage/Cost取值存在，max_cost未强制且已明示；PII/工具有严格固定码；guard使用全局get_tracer，主Agent使用显式Provider | opt-in隔离环境验证Azure检出/误报/长上下文/重试、真实模型claim质量与价格可得性；Fake exporter补PII span/入口统计；核对native hooks与锁版本 | 不将22个脚本场景通过当真实模型能力评估；不承诺USD硬上限、零PII误报或已经验证真实多服务生产运行。 |

以上事项不妨碍完成本轮源码审计，但限制了对生产暴露、隔离级别行为和真实模型质量的判断。已确认代码缺陷的修复不应等到发生事故才开始。

## H. 最终优先级、执行建议与保留项

### H.1 数量与优先级依据

| 分类 | P0 | P1 | P2 | 合计 |
|---|---:|---:|---:|---:|
| 已确认问题 | 15 | 23 | 2 | **40** |
| 有条件结构建议（F08/F26） | 0 | 2 | 0 | 2 |
| 待验证事项（V01–V04，不计问题数量） | — | — | — | 4 |

P0 为现有内容/授权边界、证据归属、最终业务语义或数据一致性/恢复不变量可被破坏；不是按类大或依赖多来排。P1 是可维护性、可测试性、业务读模型真实性与有明确触发条件的功能完整性；P2 是无效依赖/旧能力清理，收益有限，不应抢在安全和数据问题之前。

其余 P0（F05、F16、F17、F21、F22）仍须在启用相关业务前修复，不能因未进入下面前十而推迟为普通优化。

### H.2 最值得优先处理的 10 项

| 顺序 | 问题 | 优先处理的原因 | 完成标准 |
|---:|---|---|---|
| 1 | F01：实际模型请求/Schema Retry PII缺口 | 现有业务回调无法覆盖框架自动重试内容 | 禁字段/PII的Schema错误和未知Tool内容不能进入下一次真实模型请求 |
| 2 | F02：完整Proposal/Tool输入安全 | 不可信方案字段会保存/返回，局部投影已证实漏检 | 全字符串和完整DSL出站/保存前检查；固定错误，无敏感持久化 |
| 3 | F03：统一运行状态收敛 | 出错/超时后无人持有RUNNING，无法续查/取消 | 初始化、模型、最终检查、持久化、关闭的全部路径有确定终态或显式未收敛记录，容量不泄漏 |
| 4 | F04：Scope与并行采证一致性 | 可把旧范围事实升级为新范围证据 | 真并行Tool场景不误标版本，历史证据无法证明新结论 |
| 5 | F13：Draft并发确认/编辑事务 | 可能重复Campaign及终态覆盖，直接影响业务写入 | 真实MySQL双确认/编辑竞态最多一个Campaign，状态与Rules一致 |
| 6 | F14：无owner历史Draft授权 | 已登录即能使用无归属资源 | Java独立fail-closed；历史隔离/管理员认领有明确流程和回归 |
| 7 | F18：事件+指标回滚 | 部分提交后重试duplicate路径无法修复缺桶 | insert后任何指标/解析失败全部回滚；重投后事实与桶一致 |
| 8 | F19：规则失败分类与未知类型 | 依赖故障被当业务成功，未知规则可放行 | 非法规则拒绝，临时故障触发补偿/重试，不创建越界触达 |
| 9 | F20：归因建任务/processing恢复闭环 | ACK/Phase2重放/崩溃可永久丢归因工作 | 正常、补偿、未建DB、claim后硬崩溃均最终收敛或进入可见失败 |
| 10 | F15：Java DSL校验、预估、执行一致 | 人工确认与实际规则不符 | OR/Decimal/Integer等要么完整支持要么明确拒绝，三阶段结果等价 |

### H.3 后续一次实施的组织方式

建议冻结本报告的源代码基准、Tool Schema、API契约和存储结构后，按以下闭环组织一次实施。每个闭环同时完成相关方法迁移、全部调用方更新和测试，不长期保留两套实现；它们可以作为同次交付的分组提交，而不是推翻框架的大迁移。

1. **Python安全与运行闭环**：F01–F12、F35/F36。先加实证回归，统一Service运行所有权，移动Workspace/上下文，进入官方模型生命周期；F08 Tool提取按收益决定。保持API/DB/11个工具契约。
2. **Java Proposal/Draft与可执行配置闭环**：F13–F17、F23–F25。独立应用编排与写事务，保留提交后修复；补锁/CAS和最终语义，完成真实MySQL并发测试。拒绝不支持的配置是显式行为修复，单列兼容性。
3. **事件、画像、任务恢复闭环**：F18–F22、F30/F31、F37/F39/F40/F42。正常/补偿复用业务阶段，修完整队列与重试状态，Profile拥有画像，校验事实最终结果；CLICK增量迁移若必需单独说明。
4. **读模型、Web与工程收口**：F27–F29、F32/F33、F38/F41；有收益时做F26。统一口径/未知状态/前端请求身份；再处理F34/F36旧能力。切勿将报错“优化”为成功或0。
5. **统一验收**：G.1/G.3全部关键安全和数据不变量 + Java/Python/Web现有检查 + 隔离MySQL/Kafka/Redis故障注入 + 浏览器Proposal→Draft→confirm真实链路。真实Provider质量单独opt-in，不让普通CI产生付费调用。

这套组织不要求新建无真实用途的接口、基类、抽象工厂或通用Manager，不更换Pydantic AI/FastAPI/Spring/MyBatis/SQLAlchemy，也不把所有SQL、validate/check/guard集中到一个包。

### H.4 建议保留的现有设计

- 一个自主选Tool的Pydantic AI Agent，FunctionModel只用于测试；官方Schema/Retry/Usage/Instrumentation优先。
- 六类Java聚合只读工具、类型化请求/响应、固定错误码；Python无业务库凭证、不拼SQL、不发执行命令。
- Evidence带Java queryId、source、dataVersion、scope、Trace关联；历史证据保留为背景，引用规则修复后继续维护。
- Hypothesis、Diagnosis、Investigation运行状态与可见消息的区分；续查沿用ID而非覆盖历史。
- Repository的begin_followup CAS、finish状态+消息同事务、轨迹锁追加，以及DB成功后再更新内存的基本顺序。
- Java会话取得operator、调查归属独立于Agent数据库、Proposal owner/证据/DSL再校验；前端按钮不能替代授权。
- Proposal持久化后停止；用户单独生成Draft、再人工确认，最终Campaign仍DRAFT；没有自动激活/发送。
- Java已提交Draft优先幂等返回，Agent关联回写失败不撤销Java结果；将编排迁应用层，不破坏这一语义。
- EventPersistenceService与CompensationClaimService作为独立Spring Bean的真实事务边界；canonical事件、业务dedup、渠道业务唯一键、Redis原子Lua/额度reservation/after-commit归因调度。
- 现有共享RealtimeProfile Lua（修复行为并迁正确模块），以及独立延迟/归因队列各自业务不变量；不因为脚本相似强制合一。
- SQL维度/字段白名单与命名参数；静态JsonUtil/DedupKeyUtil/常量是实际复用，不因静态或单方法而机械删除。
- OTel禁内容采集和白名单安全导出；SSE仅业务事件，Web用GET权威快照；Vue文本转义和明确demo模式。
- testing对数据库/主机/命名空间的隔离保护、Functional与Performance分工、NOT_RUN而非虚假通过的设计方向（补F41证据门）。

### H.5 覆盖与交付验收

- [x] 已覆盖全部当前有效生产实现、相关测试、配置、迁移、CI/部署及工程脚本，不局限13个候选。
- [x] 已阅读关键方法实现、调用方/被调用方/依赖/测试，并追踪请求至结果；DTO/实体/Getter/简单委托按类或能力整体评估。
- [x] 启动、资源初始化、请求准入、Agent、Tool、Domain、Workspace、Repository、安全、Java最终写入、SSE和关闭均有专项分析。
- [x] 40项确认问题有源码方法/位置；有条件提取与4项未知分别列出，不以文件长度制造问题。
- [x] 推荐结构明确方法去向与原因；Maven方向和Python依赖提案无明显循环，关键授权/业务校验不下放Python或Controller。
- [x] 原生产/测试源码未修改；未提交、合并、发PR或开启重构。仅新增本Markdown报告，测试产生ignored构建产物/系统临时验证文件。
- [x] 已列未运行的真实存储、模型及端到端范围，未用历史报告或脚本控制流替代真实业务证据。

报告路径：`C:/workbuddy-workspace/PulseFlow/docs/architecture/responsibility-and-boundary-audit.md`。

最终Git/文件保护复核记录见附录末尾；本轮生产代码和测试源码修改数量为0。

## 附录 I. 全有效源码声明与调用定位索引

索引来自当前源码的 Python ast、JDK 17 JavacTask.parse、项目 TypeScript 5.8.3 解析器，解析错误均为0。Python 46文件/370个函数或方法声明/3546个调用表达式；Java 187文件/625个方法或显式构造声明/7126个调用表达式；Web 36个TS/Vue脚本文件/128个命名函数/501个调用表达式。统计含相关测试、嵌套/匿名类或回调，不表示发现相应数量问题。Lombok生成的Getter/Setter不展开，DTO/枚举按类评估。

下表列全部生产声明文件及源码行号，调用栏只选摘跨能力表达式；完整关键请求链、真实运行回调和方法归属见A/F。静态调用表达式没有假装完成动态分派的类型证明，Spring、FastAPI和Pydantic AI入口另以实际注册代码/测试核验。

### I.1 Python生产与迁移

| 文件 | 类/结构声明 | 函数/方法:行号 | 关键调用表达式（选摘） |
|---|---|---|---|
| [pulseflow-agent/migrations/env.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/env.py) | — | `run_migrations_offline:23`、`do_run_migrations:30`、`run_async_migrations:37` | `context.configure`、`context.run_migrations`、`connection.run_sync`、`asyncio.run` |
| [pulseflow-agent/migrations/versions/0001_agent_workspace.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/versions/0001_agent_workspace.py) | — | `upgrade:13`、`downgrade:76` | `op.create_table`、`op.create_index` |
| [pulseflow-agent/migrations/versions/0002_campaign_proposals.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/versions/0002_campaign_proposals.py) | — | `upgrade:13`、`downgrade:36` | `op.create_table`、`op.create_index` |
| [pulseflow-agent/migrations/versions/0003_proposal_boundary.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/versions/0003_proposal_boundary.py) | — | `upgrade:15`、`downgrade:41` | `op.add_column`、`connection.execute(sa.text('SELECT id, draft_json FROM agent_campaign_proposal')).mappings().all`、`connection.execute(sa.text('SELECT id, draft_json FROM agent_campaign_proposal')).mappings`、`connection.execute`、`json.loads` |
| [pulseflow-agent/src/pulseflow_agent/__init__.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/__init__.py) | — | — | — |
| [pulseflow-agent/src/pulseflow_agent/agent/dependencies.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/dependencies.py) | `OperatorContext:14`、`ProposalContext:21`、`AgentDependencies:29` | — | — |
| [pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/growth_investigator.py) | `GrowthInvestigator:100` | `create_model:56`、`create_usage_limits:90`、`GrowthInvestigator.__init__:102`、`GrowthInvestigator._save:126`、`GrowthInvestigator._register_tools:157`、`_register_tools.investigation_context:162`、`_register_tools.query_metric:176`、`_register_tools.compare_metric:191`、`_register_tools.breakdown_metric:206`、`_register_tools.get_campaign_performance:221`、`_register_tools.get_attribution_breakdown:244`、`_register_tools.preview_audience:263`、`_register_tools.propose_hypothesis:278`、`_register_tools.update_hypothesis:297`、`_register_tools.list_hypotheses:325`、`_register_tools.update_scope:340`、`_register_tools.prepare_proposal:351`、`_register_tools.persist_campaign_proposal:366`、`_register_tools.validate_diagnosis:388`、`GrowthInvestigator.run:413` | `ctx.deps.guardrail.check`、`ctx.deps.workspace.add_evidence`、`ctx.deps.workspace.record_tool`、`ctx.deps.pulseflow.query_metric` |
| [pulseflow-agent/src/pulseflow_agent/agent/instructions.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/instructions.py) | — | — | — |
| [pulseflow-agent/src/pulseflow_agent/agent/observations.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/agent/observations.py) | — | `_dimensions:13`、`query_observation:23`、`compare_observation:32`、`performance_observation:43`、`attribution_observation:55`、`audience_observation:65` | — |
| [pulseflow-agent/src/pulseflow_agent/clients/pulseflow_api.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/clients/pulseflow_api.py) | `ToolClientError:31`、`PulseFlowApiClient:41` | `ToolClientError.__init__:35`、`PulseFlowApiClient.__init__:43`、`PulseFlowApiClient._unavailable:57`、`PulseFlowApiClient._request:66`、`PulseFlowApiClient.query_metric:112`、`PulseFlowApiClient.compare_metric:125`、`PulseFlowApiClient.breakdown_metric:138`、`PulseFlowApiClient.get_campaign_performance:151`、`PulseFlowApiClient.get_attribution_breakdown:164`、`PulseFlowApiClient.preview_audience:173` | `self._client.request` |
| [pulseflow-agent/src/pulseflow_agent/config.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/config.py) | `AgentSettings:10` | `AgentSettings.empty_optional_setting:47`、`AgentSettings.is_test_model:52`、`AgentSettings.validate_real_model:57` | `self.pulseflow_agent_model.startswith` |
| [pulseflow-agent/src/pulseflow_agent/domain/campaign_proposal.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/campaign_proposal.py) | `CampaignProposal:20`、`ProposalRecord:66`、`ProposalRead:79` | `CampaignProposal.valid_references:33`、`CampaignProposal.free_text:41`、`CampaignProposal.to_dsl:53` | — |
| [pulseflow-agent/src/pulseflow_agent/domain/contracts.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/contracts.py) | `WireModel:14`、`Metric:19`、`Dimension:30`、`AttributionDimension:37`、`FilterField:45`、`Operator:51`、`TimeRange:57`、`MetricFilter:71`、`QueryMetricArgs:92`、`CompareMetricArgs:101`、`BreakdownMetricArgs:118`、`AttributionArgs:127`、`AudienceCondition:135`、`AudienceGroup:143`、`CampaignSchedule:149`、`FrequencyCap:156`、`PromotionFact:162`、`CampaignDsl:172`、`PreviewAudienceArgs:184`、`CampaignPerformanceArgs:189`、`ToolMetadata:194`、`MetricRow:219`、`QueryResponse:226`、`CompareRow:236`、`CompareResponse:247`、`PerformanceResponse:258`、`AttributionRow:274`、`AttributionResponse:281`、`AudienceValidation:289`、`AudienceResponse:297` | `TimeRange.bound_window:63`、`MetricFilter.valid_values:78`、`CompareMetricArgs.no_day_comparison:111`、`safe_query:309` | — |
| [pulseflow-agent/src/pulseflow_agent/domain/investigation.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/domain/investigation.py) | `Evidence:29`、`Hypothesis:46`、`AgentMessage:62`、`Finding:70`、`Diagnosis:76`、`ToolObservation:87`、`InvestigationResult:94`、`Investigation:104`、`InvestigationWorkspace:122` | `InvestigationWorkspace.add_proposal:138`、`InvestigationWorkspace.record_tool:159`、`InvestigationWorkspace.add_evidence:165`、`InvestigationWorkspace.propose_hypothesis:189`、`InvestigationWorkspace.update_hypothesis:218`、`InvestigationWorkspace.change_scope:255`、`InvestigationWorkspace.add_message:281`、`InvestigationWorkspace.finish:295`、`InvestigationWorkspace.context_text:309`、`InvestigationWorkspace._validate_evidence:336`、`InvestigationWorkspace.evidence_ids:342` | `self.repository.add_proposal`、`self.repository.add_evidence`、`self.repository.add_hypothesis`、`self.repository.change_scope`、`self.repository.add_message` |
| [pulseflow-agent/src/pulseflow_agent/main.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/main.py) | `InvestigationRequest:40`、`FollowUpRequest:49`、`ProposalRequest:56`、`DraftCreatedRequest:62` | `create_app:68`、`create_app.lifespan:75`、`create_app.capacity_error:149`、`create_app.invalid_request:158`、`create_app.internal_auth:163`、`create_app.live:179`、`create_app.agent_quality:184`、`create_app.ready:189`、`create_app.start_investigation:200`、`create_app.resume_investigation:212`、`create_app.cancel_investigation:231`、`create_app.events:244`、`events.frame:255`、`events.stream:260`、`create_app.investigate:315`、`create_app.get_investigation:328`、`create_app.follow_up:339`、`create_app.propose:358`、`create_app.get_proposal:379`、`create_app.mark_proposal_draft:390` | `database_url.get_secret_value().startswith`、`telemetry.configure_endpoint`、`repository.create_schema_for_tests`、`repository.check_ready`、`service.shutdown` |
| [pulseflow-agent/src/pulseflow_agent/observability/tracing.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/observability/tracing.py) | `QualityMetrics:22`、`AgentLatencyProcessor:104`、`SafeSpanExporter:121`、`Telemetry:196` | `QualityMetrics.__init__:26`、`QualityMetrics.record_status:37`、`QualityMetrics.java_call:54`、`QualityMetrics.reference_check:60`、`QualityMetrics.java_contract_error:66`、`QualityMetrics.latency:71`、`QualityMetrics.snapshot:77`、`AgentLatencyProcessor.__init__:106`、`AgentLatencyProcessor.on_end:110`、`SafeSpanExporter.__init__:125`、`SafeSpanExporter.export:129`、`SafeSpanExporter.shutdown:191`、`Telemetry.__init__:198`、`Telemetry.agent_instrumentation:219`、`Telemetry.configure_endpoint:230`、`Telemetry.shutdown:242` | `self.metrics.latency`、`key.startswith`、`self.wrapped.export`、`self.wrapped.shutdown`、`Resource.create` |
| [pulseflow-agent/src/pulseflow_agent/runtime.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/runtime.py) | `AdmissionRejected:21`、`RunAdmission:26` | `RunAdmission.__init__:28`、`RunAdmission.acquire:36`、`RunAdmission.release:51` | — |
| [pulseflow-agent/src/pulseflow_agent/security/pii_guardrail.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/security/pii_guardrail.py) | `PiiBlockedError:28`、`AzurePiiGuardrail:61` | `PiiBlockedError.__init__:32`、`_texts:38`、`AzurePiiGuardrail.__init__:63`、`AzurePiiGuardrail.check:68`、`AzurePiiGuardrail._check_azure:84` | `trace.get_tracer(__name__).start_as_current_span`、`self._client.post` |
| [pulseflow-agent/src/pulseflow_agent/workspace/models.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/models.py) | — | — | — |
| [pulseflow-agent/src/pulseflow_agent/workspace/repository.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/repository.py) | `InvestigationNotFoundError:31`、`InvestigationConflictError:36`、`InvestigationRepository:41`、`SqlAlchemyInvestigationRepository:78` | `InvestigationRepository.add_proposal:43`、`InvestigationRepository.load_proposal:45`、`InvestigationRepository.mark_draft_created:47`、`InvestigationRepository.create:49`、`InvestigationRepository.load:51`、`InvestigationRepository.begin_followup:53`、`InvestigationRepository.append_tool:55`、`InvestigationRepository.add_evidence:57`、`InvestigationRepository.add_hypothesis:59`、`InvestigationRepository.update_hypothesis:61`、`InvestigationRepository.change_scope:63`、`InvestigationRepository.add_message:65`、`InvestigationRepository.finish:67`、`_utc:73`、`SqlAlchemyInvestigationRepository.__init__:80`、`SqlAlchemyInvestigationRepository._connection:92`、`SqlAlchemyInvestigationRepository.create_schema_for_tests:104`、`SqlAlchemyInvestigationRepository.check_ready:109`、`SqlAlchemyInvestigationRepository.recover_interrupted:115`、`SqlAlchemyInvestigationRepository.create:125`、`SqlAlchemyInvestigationRepository.load:145`、`SqlAlchemyInvestigationRepository.add_proposal:277`、`SqlAlchemyInvestigationRepository.load_proposal:299`、`SqlAlchemyInvestigationRepository.mark_draft_created:323`、`SqlAlchemyInvestigationRepository.begin_followup:340`、`SqlAlchemyInvestigationRepository.append_tool:370`、`SqlAlchemyInvestigationRepository.add_evidence:388`、`SqlAlchemyInvestigationRepository.add_hypothesis:413`、`SqlAlchemyInvestigationRepository.update_hypothesis:437`、`SqlAlchemyInvestigationRepository.change_scope:464`、`SqlAlchemyInvestigationRepository.add_message:489`、`SqlAlchemyInvestigationRepository.finish:511` | `engine.url.drivername.startswith`、`self._memory_lock.acquire`、`self._memory_lock.release`、`connection.run_sync`、`connection.execute` |
| [pulseflow-agent/src/pulseflow_agent/workspace/service.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/src/pulseflow_agent/workspace/service.py) | `InvestigationService:22` | `InvestigationService.__init__:24`、`InvestigationService._run:37`、`InvestigationService.start:59`、`start.factory:80`、`start.background:86`、`start.done:103`、`InvestigationService._stopped_diagnosis:117`、`InvestigationService.cancel:129`、`InvestigationService.shutdown:145`、`InvestigationService._workspace:157`、`InvestigationService.create:173`、`create.factory:175`、`InvestigationService.follow_up:183`、`follow_up.factory:190`、`InvestigationService.get:200`、`InvestigationService.propose:204`、`propose.factory:223` | `self.admission.acquire`、`self._investigator.run`、`self.admission.release`、`self._guardrail.check`、`self._repository.create` |

### I.2 Java全部生产类

| 文件 | 类/结构声明 | 方法/构造:行号 | 关键调用表达式（选摘） |
|---|---|---|---|
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentGatewayException.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentGatewayException.java) | `AgentGatewayException:6` | `AgentGatewayException.constructor:10` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentGatewayLimit.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentGatewayLimit.java) | `AgentGatewayLimit:11` | `AgentGatewayLimit.acquire:16`、`AgentGatewayLimit.rejected:29` | `starts.computeIfAbsent` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentInvestigationOwnership.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentInvestigationOwnership.java) | `AgentInvestigationOwnership:16` | `AgentInvestigationOwnership.register:22`、`AgentInvestigationOwnership.assertOwner:30`、`AgentInvestigationOwnership.lockOwner:40`、`AgentInvestigationOwnership.validId:49`、`AgentInvestigationOwnership.forbidden:56` | `jdbc.update` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentMetricService.java) | `AgentMetricService:33`、`AgentMetricService.FilterSpec:365`、`AgentMetricService.Counts:367` | `AgentMetricService.query:43`、`AgentMetricService.breakdown:64`、`AgentMetricService.compare:73`、`AgentMetricService.attribution:109`、`AgentMetricService.readCounts:135`、`AgentMetricService.bucket:174`、`AgentMetricService.attributionBucket:184`、`AgentMetricService.grouping:194`、`AgentMetricService.filterSql:200`、`AgentMetricService.parameters:208`、`AgentMetricService.parseFilters:218`、`AgentMetricService.validateRange:253`、`AgentMetricService.dimension:265`、`AgentMetricService.dimensions:272`、`AgentMetricService.rowLimit:277`、`AgentMetricService.limitedKeys:284`、`AgentMetricService.dimensionMap:291`、`AgentMetricService.key:299`、`AgentMetricService.string:304`、`AgentMetricService.number:309`、`AgentMetricService.value:314`、`AgentMetricService.sampleSize:327`、`AgentMetricService.zeroDenominatorWarning:340`、`AgentMetricService.requireMetric:350`、`AgentMetricService.invalid:355`、`AgentMetricService.metadata:360` | `facts.stream().limit(limit).map((f)->{     Map<AttributionDimension, String> dims = new LinkedHashMap<>();     dims.put(request.dimension(), string(f.get("bucket")));     return new AttributionRow(dims, number(f.get("total")), number(f.get("unique_count"))); }).toList`、`dims.put`、`result.computeIfAbsent`、`result.put`、`values.put` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalClient.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalClient.java) | `AgentProposalClient:14` | `AgentProposalClient.constructor:21`、`AgentProposalClient.read:33`、`AgentProposalClient.markDraftCreated:49`、`AgentProposalClient.ensureConfigured:62`、`AgentProposalClient.url:67` | `builder.requestFactory(factory).build`、`builder.requestFactory`、`client.put().uri(url(proposalId) + "/draft").header("X-PulseFlow-Agent-Token", token).contentType(MediaType.APPLICATION_JSON).body(java.util.Map.of("draft_id", draftId)).retrieve().toBodilessEntity`、`client.put().uri(url(proposalId) + "/draft").header("X-PulseFlow-Agent-Token", token).contentType(MediaType.APPLICATION_JSON).body(java.util.Map.of("draft_id", draftId)).retrieve`、`client.put().uri(url(proposalId) + "/draft").header("X-PulseFlow-Agent-Token", token).contentType(MediaType.APPLICATION_JSON).body` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentProposalGateway.java) | `AgentProposalGateway:29`、`AgentProposalGateway.Request:54`、`AgentProposalGateway.FollowUpRequest:56`、`AgentProposalGateway.ProposalRequest:58` | `AgentProposalGateway.constructor:41`、`AgentProposalGateway.investigate:61`、`AgentProposalGateway.get:79`、`AgentProposalGateway.followUp:86`、`AgentProposalGateway.cancel:102`、`AgentProposalGateway.events:109`、`AgentProposalGateway.shutdown:158`、`AgentProposalGateway.owned:161`、`AgentProposalGateway.url:170`、`AgentProposalGateway.call:173`、`AgentProposalGateway.publicView:193`、`AgentProposalGateway.removePrivate:203`、`AgentProposalGateway.propose:213`、`AgentProposalGateway.validate:228` | `builder.requestFactory(factory).build`、`builder.requestFactory`、`StpUtil.checkLogin`、`limits.acquire`、`payload.put` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolAuthConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolAuthConfig.java) | `AgentToolAuthConfig:10` | `AgentToolAuthConfig.agentToolAuthFilter:13` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolAuthFilter.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolAuthFilter.java) | `AgentToolAuthFilter:17` | `AgentToolAuthFilter.constructor:22`、`AgentToolAuthFilter.doFilterInternal:27`、`AgentToolAuthFilter.hash:44` | `response.sendError` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolController.java) | `AgentToolController:15` | `AgentToolController.query:23`、`AgentToolController.compare:29`、`AgentToolController.breakdown:35`、`AgentToolController.performance:41`、`AgentToolController.attribution:47`、`AgentToolController.audience:53` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolDtos.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolDtos.java) | `AgentToolDtos:14`、`AgentToolDtos.Metric:19`、`AgentToolDtos.Dimension:24`、`AgentToolDtos.AttributionDimension:27`、`AgentToolDtos.FilterField:30`、`AgentToolDtos.Operator:33`、`AgentToolDtos.TimeRange:38`、`AgentToolDtos.Filter:41`、`AgentToolDtos.QueryRequest:44`、`AgentToolDtos.CompareRequest:48`、`AgentToolDtos.BreakdownRequest:52`、`AgentToolDtos.AttributionRequest:56`、`AgentToolDtos.AudienceRequest:60`、`AgentToolDtos.Metadata:63`、`AgentToolDtos.MetricRow:69`、`AgentToolDtos.QueryResponse:72`、`AgentToolDtos.CompareRow:78`、`AgentToolDtos.CompareResponse:84`、`AgentToolDtos.PerformanceResponse:89`、`AgentToolDtos.AttributionRow:96`、`AgentToolDtos.AttributionResponse:100`、`AgentToolDtos.Validation:107`、`AgentToolDtos.AudienceResponse:111` | `AgentToolDtos.constructor:16` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolExceptionHandler.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolExceptionHandler.java) | `AgentToolExceptionHandler:21` | `AgentToolExceptionHandler.gateway:26`、`AgentToolExceptionHandler.unauthorized:31`、`AgentToolExceptionHandler.forbidden:36`、`AgentToolExceptionHandler.conflict:41`、`AgentToolExceptionHandler.invalid:46`、`AgentToolExceptionHandler.missing:52`、`AgentToolExceptionHandler.unavailable:58`、`AgentToolExceptionHandler.failed:65` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/AgentToolService.java) | `AgentToolService:32` | `AgentToolService.performance:42`、`AgentToolService.audience:69`、`AgentToolService.toInstant:93` | `campaigns.selectById`、`summaries.selectOne`、`validator.validate`、`checked.getMissingFields().stream().filter(Set.of("promotionFacts", "schedule.sendAt")::contains).toList`、`previewService.preview` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftController.java) | `CampaignProposalDraftController:15`、`CampaignProposalDraftController.Response:23` | `CampaignProposalDraftController.create:26` | `StpUtil.checkLogin`、`drafts.create` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/agenttools/CampaignProposalDraftService.java) | `CampaignProposalDraftService:26`、`CampaignProposalDraftService.Proposal:37` | `CampaignProposalDraftService.create:43`、`CampaignProposalDraftService.find:104`、`CampaignProposalDraftService.ownedDraft:110` | `validator.validate`、`preview.preview`、`warning.startsWith`、`drafts.createDraft`、`draftMapper.updateById` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/AgentTelemetryConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/AgentTelemetryConfig.java) | `AgentTelemetryConfig:25`、`AgentTelemetryConfig.SafeSpanExporter:35`、`SafeSpanExporter.(anonymous):48` | `AgentTelemetryConfig.privacyCustomizer:28`、`SafeSpanExporter.constructor:43`、`SafeSpanExporter.export:46`、`(anonymous).getResource:50`、`(anonymous).getAttributes:59`、`(anonymous).getEvents:67`、`(anonymous).getLinks:69`、`(anonymous).getStatus:71`、`SafeSpanExporter.put:79`、`SafeSpanExporter.flush:84`、`SafeSpanExporter.shutdown:86` | `spans.stream().map((span)->(SpanData)new DelegatingSpanData(span){          @Override     public Resource getResource() {         AttributesBuilder builder = Attributes.builder();         span.getResource().getAttributes().forEach((key,value)->{             if (key.getKey().equals("service.name") &#124;&#124; key.getKey().equals("service.version") &#124;&#124; key.getKey().startsWith("telemetry.sdk.")) put(builder, key, value);         });         return Resource.create(builder.build());     }          @Override     public Attributes getAttributes() {         AttributesBuilder builder = Attributes.builder();         span.getAttributes().forEach((key,value)->{             if (ALLOWED.contains(key.getKey())) put(builder, key, value);         });         return builder.build();     }          @Override     public List<EventData> getEvents() {         return List.of();     }          @Override     public List<LinkData> getLinks() {         return List.of();     }          @Override     public StatusData getStatus() {         return StatusData.create(span.getStatus().getStatusCode(), "");     } }).toList`、`key.getKey().startsWith`、`Resource.create`、`StatusData.create`、`delegate.export` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/DevLoginController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/DevLoginController.java) | `DevLoginController:31`、`DevLoginController.DevLoginResponse:66` | `DevLoginController.login:44`、`DevLoginController.loginGet:60` | `data.put` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/GlobalExceptionHandler.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/GlobalExceptionHandler.java) | `GlobalExceptionHandler:18` | `GlobalExceptionHandler.handleNotLogin:23`、`GlobalExceptionHandler.handlePulseFlowException:30`、`GlobalExceptionHandler.handleDuplicateEvent:38`、`GlobalExceptionHandler.handleCampaignNotFound:45`、`GlobalExceptionHandler.handleCampaignConflict:52`、`GlobalExceptionHandler.handleCampaignForbidden:59`、`GlobalExceptionHandler.handleValidation:66`、`GlobalExceptionHandler.handleBadRequest:76`、`GlobalExceptionHandler.handleGeneral:83` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/KafkaConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/KafkaConfig.java) | `KafkaConfig:9` | `KafkaConfig.rawEventsTopic:13`、`KafkaConfig.deliveryTopic:22` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/LocalAuthSessionConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/LocalAuthSessionConfig.java) | `LocalAuthSessionConfig:18` | `LocalAuthSessionConfig.localAuthSaTokenDao:26` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/MyBatisPlusConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/MyBatisPlusConfig.java) | `MyBatisPlusConfig:10` | `MyBatisPlusConfig.mybatisPlusInterceptor:14` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/RedisConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/RedisConfig.java) | `RedisConfig:11` | `RedisConfig.redissonClient:27` | `Redisson.create` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/SaTokenConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/SaTokenConfig.java) | `SaTokenConfig:11` | `SaTokenConfig.addInterceptors:15` | `registry.addInterceptor(new SaInterceptor((handle)->{     SaRouter.match("/api/**", (r)->StpUtil.checkLogin());     SaRouter.match("/api/events", (r)->{     }); })).addPathPatterns("/**").excludePathPatterns`、`registry.addInterceptor(new SaInterceptor((handle)->{     SaRouter.match("/api/**", (r)->StpUtil.checkLogin());     SaRouter.match("/api/events", (r)->{     }); })).addPathPatterns`、`StpUtil.checkLogin` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/XxlJobConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/config/XxlJobConfig.java) | `XxlJobConfig:10` | `XxlJobConfig.xxlJobExecutor:33` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/PulseFlowApplication.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/PulseFlowApplication.java) | `PulseFlowApplication:10` | `PulseFlowApplication.main:25` | `SpringApplication.run` |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/AuthController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/AuthController.java) | `AuthController:18`、`AuthController.LoginRequest:79` | `AuthController.login:26`、`AuthController.logout:39`、`AuthController.me:46`、`AuthController.toSession:57`、`AuthController.currentLoginId:69` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/LocalAuthProperties.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/LocalAuthProperties.java) | `LocalAuthProperties:20`、`LocalAuthProperties.AuthenticatedOperator:72` | `LocalAuthProperties.authenticate:34`、`LocalAuthProperties.describe:48`、`LocalAuthProperties.matches:62` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebDtos.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebDtos.java) | `WebDtos:20`、`WebDtos.PageResponse:27`、`WebDtos.DashboardSummary:45`、`WebDtos.TrendPoint:58`、`WebDtos.DashboardTrends:62`、`WebDtos.CampaignListItem:76`、`WebDtos.CampaignView:92`、`WebDtos.RuleView:115`、`WebDtos.AudienceView:126`、`WebDtos.DeliverySummary:139`、`WebDtos.AttributionSummary:151`、`WebDtos.CampaignDetail:159`、`WebDtos.PerformanceView:173`、`WebDtos.UserListItem:190`、`WebDtos.UserProfileView:207`、`WebDtos.UserDetail:218`、`WebDtos.EventView:236`、`WebDtos.DeliveryListItem:255`、`WebDtos.DeliveryRecordView:271`、`WebDtos.ClickView:284`、`WebDtos.AttributionView:299`、`WebDtos.DeliveryDetail:314`、`WebDtos.SystemStatus:328`、`WebDtos.AuthSession:337` | `WebDtos.constructor:23`、`PageResponse.constructor:35`、`DashboardTrends.constructor:68`、`AudienceView.constructor:133`、`CampaignDetail.constructor:167`、`UserListItem.constructor:201`、`UserDetail.constructor:228`、`EventView.constructor:249`、`ClickView.constructor:293`、`DeliveryDetail.constructor:321` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryController.java) | `WebQueryController:15` | `WebQueryController.dashboardSummary:23`、`WebQueryController.dashboardTrends:29`、`WebQueryController.campaigns:36`、`WebQueryController.campaign:50`、`WebQueryController.campaignPerformance:57`、`WebQueryController.campaignPerformanceTrend:64`、`WebQueryController.campaignDeliveries:72`、`WebQueryController.campaignAttributions:85`、`WebQueryController.users:94`、`WebQueryController.user:104`、`WebQueryController.userProfile:113`、`WebQueryController.userEvents:119`、`WebQueryController.events:132`、`WebQueryController.event:145`、`WebQueryController.deliveries:151`、`WebQueryController.delivery:166`、`WebQueryController.attributions:172`、`WebQueryController.attribution:185`、`WebQueryController.systemStatus:191`、`WebQueryController.currentOperatorId:197` | — |
| [pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/java/com/pulseflow/boot/web/WebQueryService.java) | `WebQueryService:60`、`WebQueryService.RealtimeResult:842`、`WebQueryService.PerformanceNumbers:846` | `WebQueryService.dashboardSummary:83`、`WebQueryService.dashboardTrends:109`、`WebQueryService.listCampaigns:133`、`WebQueryService.campaignDetail:163`、`WebQueryService.campaignPerformance:183`、`WebQueryService.campaignDeliveryTrend:189`、`WebQueryService.campaignDeliveries:216`、`WebQueryService.campaignAttributions:223`、`WebQueryService.listUsers:230`、`WebQueryService.userDetail:250`、`WebQueryService.userProfile:264`、`WebQueryService.listUserEvents:271`、`WebQueryService.listEvents:278`、`WebQueryService.event:297`、`WebQueryService.listDeliveries:307`、`WebQueryService.delivery:335`、`WebQueryService.listAttributions:356`、`WebQueryService.attribution:378`、`WebQueryService.systemStatus:387`、`WebQueryService.toCampaignView:396`、`WebQueryService.toRuleView:407`、`WebQueryService.toUserListItem:414`、`WebQueryService.toEventView:427`、`WebQueryService.toDeliveryListItem:435`、`WebQueryService.toDeliveryRecordView:446`、`WebQueryService.toClickView:453`、`WebQueryService.toAttributionView:459`、`WebQueryService.windowMetrics:469`、`WebQueryService.realtimeMetrics:489`、`WebQueryService.activeTags:512`、`WebQueryService.performanceNumbers:521`、`WebQueryService.audienceFromDraft:554`、`WebQueryService.findCampaign:566`、`WebQueryService.findUser:575`、`WebQueryService.hourlyTrend:585`、`WebQueryService.dailyTrend:598`、`WebQueryService.queryBuckets:611`、`WebQueryService.count:625`、`WebQueryService.mysqlStatus:636`、`WebQueryService.redisStatus:646`、`WebQueryService.kafkaStatus:659`、`WebQueryService.parseJson:664`、`WebQueryService.canonicalMetric:683`、`WebQueryService.canonicalRealtimeMetric:695`、`WebQueryService.page:707`、`WebQueryService.safePage:713`、`WebQueryService.safePageSize:718`、`WebQueryService.hasText:723`、`WebQueryService.trim:728`、`WebQueryService.parseDateTime:733`、`WebQueryService.parseLong:749`、`WebQueryService.number:761`、`WebQueryService.numberObject:773`、`WebQueryService.integer:778`、`WebQueryService.bool:783`、`WebQueryService.decimal:791`、`WebQueryService.ratio:806`、`WebQueryService.localDateTime:814`、`WebQueryService.string:832`、`WebQueryService.decimal:837`、`PerformanceNumbers.toDeliverySummary:860`、`PerformanceNumbers.toView:866` | `campaignMapper.selectCount`、`campaignMapper.selectPage`、`result.getRecords().stream().map((campaign)->{     PerformanceNumbers p = performanceNumbers(campaign.getId());     return new WebDtos.CampaignListItem(campaign.getId(), campaign.getName(), campaign.getStatus(), campaign.getTriggerType(), campaign.getChannel(), p.audience(), p.sent(), p.clicked(), p.converted(), campaign.getCreatedAt(), campaign.getCreatedBy()); }).toList`、`p.converted`、`campaignRuleMapper.selectList(new LambdaQueryWrapper<CampaignRule>().eq(CampaignRule::getCampaignId, campaignId).orderByAsc(CampaignRule::getPriority, CampaignRule::getId)).stream().map(this::toRuleView).toList` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/AudienceMetrics.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/AudienceMetrics.java) | `AudienceMetrics:19` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/AudienceMetricsAggregator.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/AudienceMetricsAggregator.java) | `AudienceMetricsAggregator:39` | `AudienceMetricsAggregator.aggregate:50`、`AudienceMetricsAggregator.resolveMatched:114`、`AudienceMetricsAggregator.evalOne:134`、`AudienceMetricsAggregator.computeTagRatios:173`、`AudienceMetricsAggregator.computeMetricPositiveRate:190`、`AudienceMetricsAggregator.computeMetricAverage:209`、`AudienceMetricsAggregator.computeCartWithoutPurchaseRate:228`、`AudienceMetricsAggregator.emptyMetrics:263`、`AudienceMetricsAggregator.compare:268`、`AudienceMetricsAggregator.toDouble:281`、`AudienceMetricsAggregator.ratio:288` | `userProfileMapper.selectList`、`baseline.put`、`userTagMapper.selectList`、`behaviorSummaryMapper.selectList`、`latest.putIfAbsent` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/CampaignPerformanceSummary.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/CampaignPerformanceSummary.java) | `CampaignPerformanceSummary:23` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/CampaignPerformanceSummaryMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/CampaignPerformanceSummaryMapper.java) | `CampaignPerformanceSummaryMapper:9` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculator.java) | `PerformanceSummaryCalculator:49` | `PerformanceSummaryCalculator.compute:66`、`PerformanceSummaryCalculator.computeHistoricalBaseline:171`、`PerformanceSummaryCalculator.rate:195` | `summaryMapper.selectOne`、`campaignMapper.selectById`、`deliveryTaskMapper.selectList`、`deliveryRecordMapper.selectList`、`clickEventMapper.selectList` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionService.java) | `AttributionService:33`、`AttributionService.ExecutionResult:53`、`AttributionService.(anonymous):421` | `AttributionService.onTargetEvent:152`、`AttributionService.recoverOrphanedPendingTask:186`、`AttributionService.claimExpiredTasks:217`、`AttributionService.completeClaimedTask:235`、`AttributionService.requeueClaimedTask:246`、`AttributionService.executeAttribution:275`、`AttributionService.reconcilePendingTasks:369`、`AttributionService.schedulePendingTaskAfterCommit:408`、`(anonymous).afterCommit:423`、`AttributionService.ensurePendingTask:449`、`AttributionService.markTaskMatched:461`、`AttributionService.markTaskExpired:472`、`AttributionService.requireTaskUpdate:482`、`AttributionService.toEpochMillis:490` | `attributionTaskMapper.insert`、`attributionTaskMapper.selectOne`、`clickEventMapper.selectList`、`deliveryRecordMapper.selectOne`、`attributionRecordMapper.insert` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionTaskConsumer.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionTaskConsumer.java) | `AttributionTaskConsumer:11` | `AttributionTaskConsumer.processAttributionTasks:29`、`AttributionTaskConsumer.reconcileOrphanedTasks:84` | `attributionService.claimExpiredTasks`、`attributionService.executeAttribution`、`attributionService.requeueClaimedTask`、`attributionService.completeClaimedTask` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionTaskNotFoundException.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/AttributionTaskNotFoundException.java) | `AttributionTaskNotFoundException:10` | `AttributionTaskNotFoundException.constructor:13` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/attribution/ClickEventService.java) | `ClickEventService:12` | `ClickEventService.recordClick:24` | `clickEventMapper.insert` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/content/CampaignContentValidator.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/content/CampaignContentValidator.java) | `CampaignContentValidator:34` | `CampaignContentValidator.validate:66`、`CampaignContentValidator.collectForbidden:155`、`CampaignContentValidator.collectAllowedNumbers:174`、`CampaignContentValidator.addNumber:217`、`CampaignContentValidator.hasUnknownNumber:228`、`CampaignContentValidator.anyMatch:241`、`CampaignContentValidator.containsIgnoreCase:249`、`CampaignContentValidator.safe:255`、`CampaignContentValidator.asInt:258`、`CampaignContentValidator.asBoolean:266` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/content/ContentResult.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/content/ContentResult.java) | `ContentResult:18` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/content/ContentVariant.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/content/ContentVariant.java) | `ContentVariant:16` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/decision/DecisionEngine.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/decision/DecisionEngine.java) | `DecisionEngine:31` | `DecisionEngine.evaluate:58`、`DecisionEngine.isBusinessException:169`、`DecisionEngine.evaluateBatch:185`、`DecisionEngine.loadRules:228`、`DecisionEngine.allRulesMatched:237`、`DecisionEngine.inActiveWindow:247`、`DecisionEngine.evaluateRule:255`、`DecisionEngine.compare:303`、`DecisionEngine.getProperties:318`、`DecisionEngine.toLong:326` | `campaignMapper.selectList`、`DeliveryTaskDto.builder().campaignId(campaign.getId()).userId(userId).dedupKey(dedupKey).triggerEventId(eventId).channel(campaign.getChannel()).messageContent(campaign.getMessageTemplate()).createdAt(LocalDateTime.now()).build`、`DeliveryTaskDto.builder().campaignId(campaign.getId()).userId(userId).dedupKey(dedupKey).triggerEventId(eventId).channel(campaign.getChannel()).messageContent(campaign.getMessageTemplate()).createdAt`、`deliveryService.createDeliveryTask`、`DeliveryTaskDto.builder().campaignId(campaign.getId()).userId(userId).dedupKey(dedupKey).channel(campaign.getChannel()).messageContent(campaign.getMessageTemplate()).createdAt(LocalDateTime.now()).build` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delay/DelayedTaskExecutor.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delay/DelayedTaskExecutor.java) | `DelayedTaskExecutor:37` | `DelayedTaskExecutor.executeDue:56`、`DelayedTaskExecutor.processOne:88`、`DelayedTaskExecutor.isCartItemPresent:155` | `delayedTaskManager.claimTasks`、`delayedTaskManager.completeTask`、`campaignMapper.selectById`、`DeliveryTaskDto.builder().campaignId(campaignId).userId(userId).dedupKey(taskId).triggerEventId(addCartEventId).channel(campaign.getChannel()).messageContent(campaign.getMessageTemplate()).createdAt(LocalDateTime.now()).build`、`DeliveryTaskDto.builder().campaignId(campaignId).userId(userId).dedupKey(taskId).triggerEventId(addCartEventId).channel(campaign.getChannel()).messageContent(campaign.getMessageTemplate()).createdAt` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delay/DelayedTaskManager.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delay/DelayedTaskManager.java) | `DelayedTaskManager:14` | `DelayedTaskManager.addDelayedTask:30`、`DelayedTaskManager.claimTasks:42`、`DelayedTaskManager.completeTask:78`、`DelayedTaskManager.recoverStuckTasks:88` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryConsumer.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryConsumer.java) | `DeliveryConsumer:26` | `DeliveryConsumer.consume:41`、`DeliveryConsumer.sendViaChannel:114`、`DeliveryConsumer.sendInApp:133`、`DeliveryConsumer.sendPush:154`、`DeliveryConsumer.sendEmail:178`、`DeliveryConsumer.upsertDeliveryRecord:217`、`DeliveryConsumer.parseTask:235` | `frequencyControlService.check`、`deliveryTaskMapper.update`、`new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryTask>().eq(DeliveryTask::getId, task.getId()).set(DeliveryTask::getStatus, TaskStatus.CANCELLED.name()).set`、`new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryTask>().eq(DeliveryTask::getId, task.getId()).set`、`new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeliveryTask>().eq` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/DeliveryService.java) | `DeliveryService:21` | `DeliveryService.createDeliveryTask:37`、`DeliveryService.publishToKafka:72` | `DeliveryTask.builder().campaignId(dto.getCampaignId()).userId(dto.getUserId()).dedupKey(dto.getDedupKey()).triggerEventId(dto.getTriggerEventId()).channel(dto.getChannel()).status(TaskStatus.PENDING.name()).dispatchStatus(DispatchStatus.PENDING.name()).messageContent(dto.getMessageContent()).retryCount(0).createdAt(LocalDateTime.now()).build`、`DeliveryTask.builder().campaignId(dto.getCampaignId()).userId(dto.getUserId()).dedupKey(dto.getDedupKey()).triggerEventId(dto.getTriggerEventId()).channel(dto.getChannel()).status(TaskStatus.PENDING.name()).dispatchStatus(DispatchStatus.PENDING.name()).messageContent(dto.getMessageContent()).retryCount(0).createdAt`、`deliveryTaskMapper.insert`、`kafkaTemplate.send`、`deliveryTaskMapper.update` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/FrequencyControlService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/delivery/FrequencyControlService.java) | `FrequencyControlService:20`、`FrequencyControlService.FreqResult:39` | `FreqResult.ok:46`、`FreqResult.retryOk:48`、`FreqResult.userLimit:50`、`FreqResult.campaignLimit:52`、`FrequencyControlService.check:73` | `campaignMapper.selectById` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraft.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraft.java) | `CampaignDraft:20` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftController.java) | `CampaignDraftController:16`、`CampaignDraftController.CreateRequest:23`、`CampaignDraftController.UpdateRequest:25`、`CampaignDraftController.DraftResponse:27`、`CampaignDraftController.ConfirmResponse:31` | `CampaignDraftController.create:34`、`CampaignDraftController.get:40`、`CampaignDraftController.update:46`、`CampaignDraftController.refreshPreview:52`、`CampaignDraftController.confirm:58`、`CampaignDraftController.operatorId:65`、`CampaignDraftController.toResponse:74` | `drafts.createDraft`、`request.requestId`、`drafts.loadDraft`、`drafts.updateDraft(id, operatorId(), request.dsl()).draft`、`drafts.updateDraft` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftMapper.java) | `CampaignDraftMapper:9` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/draft/CampaignDraftService.java) | `CampaignDraftService:45`、`CampaignDraftService.DraftUpdateResult:391`、`CampaignDraftService.ConfirmResult:398` | `CampaignDraftService.createDraft:63`、`CampaignDraftService.createDraft:80`、`CampaignDraftService.updateDraft:117`、`CampaignDraftService.refreshPreview:153`、`CampaignDraftService.confirmAndCreate:202`、`CampaignDraftService.loadDraft:278`、`CampaignDraftService.loadDraftOrThrow:287`、`CampaignDraftService.requireDraftOwner:303`、`CampaignDraftService.pickStatus:313`、`CampaignDraftService.combinedWarnings:320`、`CampaignDraftService.isExpired:327`、`CampaignDraftService.markExpired:332`、`CampaignDraftService.pickDailyLimit:342`、`CampaignDraftService.pickWeeklyLimit:351`、`CampaignDraftService.buildDescription:360`、`CampaignDraftService.buildMessageTemplate:373` | `validator.validate`、`audiencePreviewService.preview`、`CampaignDraft.builder().requestId(requestId).operatorId(operatorId).sourceText(sourceText == null ? "" : sourceText).schemaVersion(dsl.getSchemaVersion() == null ? 1 : dsl.getSchemaVersion()).dslJson(JsonUtil.toJson(dsl)).validationStatus(status.name()).validationErrorsJson(validation.getErrors().isEmpty() ? null : JsonUtil.toJson(validation.getErrors())).warningsJson(JsonUtil.toJson(combinedWarnings(validation, preview))).estimatedAudienceCount(preview == null ? null : preview.getEstimatedCount()).profileDataVersion(preview == null ? null : preview.getDataVersion()).createdAt(now).updatedAt(now).expiresAt(expiresAt).build`、`CampaignDraft.builder().requestId(requestId).operatorId(operatorId).sourceText(sourceText == null ? "" : sourceText).schemaVersion(dsl.getSchemaVersion() == null ? 1 : dsl.getSchemaVersion()).dslJson(JsonUtil.toJson(dsl)).validationStatus(status.name()).validationErrorsJson(validation.getErrors().isEmpty() ? null : JsonUtil.toJson(validation.getErrors())).warningsJson(JsonUtil.toJson(combinedWarnings(validation, preview))).estimatedAudienceCount(preview == null ? null : preview.getEstimatedCount()).profileDataVersion(preview == null ? null : preview.getDataVersion()).createdAt(now).updatedAt(now).expiresAt`、`CampaignDraft.builder().requestId(requestId).operatorId(operatorId).sourceText(sourceText == null ? "" : sourceText).schemaVersion(dsl.getSchemaVersion() == null ? 1 : dsl.getSchemaVersion()).dslJson(JsonUtil.toJson(dsl)).validationStatus(status.name()).validationErrorsJson(validation.getErrors().isEmpty() ? null : JsonUtil.toJson(validation.getErrors())).warningsJson(JsonUtil.toJson(combinedWarnings(validation, preview))).estimatedAudienceCount(preview == null ? null : preview.getEstimatedCount()).profileDataVersion(preview == null ? null : preview.getDataVersion()).createdAt(now).updatedAt` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/AudienceCondition.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/AudienceCondition.java) | `AudienceCondition:19` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/AudienceGroup.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/AudienceGroup.java) | `AudienceGroup:18` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/CampaignDsl.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/CampaignDsl.java) | `CampaignDsl:28` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/CampaignSchedule.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/CampaignSchedule.java) | `CampaignSchedule:18` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/DraftStatus.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/DraftStatus.java) | `DraftStatus:22` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/DslValidationResult.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/DslValidationResult.java) | `DslValidationResult:19` | `DslValidationResult.ok:39`、`DslValidationResult.needsConfirmation:48`、`DslValidationResult.invalid:58` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/FrequencyCap.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/FrequencyCap.java) | `FrequencyCap:17` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/Operator.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/Operator.java) | `Operator:11` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/PromotionFact.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/PromotionFact.java) | `PromotionFact:19` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/ValueType.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/dsl/ValueType.java) | `ValueType:8` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/exception/CampaignConflictException.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/exception/CampaignConflictException.java) | `CampaignConflictException:10` | `CampaignConflictException.constructor:12` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/exception/CampaignForbiddenException.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/exception/CampaignForbiddenException.java) | `CampaignForbiddenException:9` | `CampaignForbiddenException.constructor:11` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/exception/CampaignResourceNotFoundException.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/exception/CampaignResourceNotFoundException.java) | `CampaignResourceNotFoundException:9` | `CampaignResourceNotFoundException.constructor:11` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/AudiencePreviewResult.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/AudiencePreviewResult.java) | `AudiencePreviewResult:20` | — | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/AudiencePreviewService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/AudiencePreviewService.java) | `AudiencePreviewService:12` | `AudiencePreviewService.preview:15`、`AudiencePreviewService.previewUserIds:24` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/SqlAudiencePreviewService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/preview/SqlAudiencePreviewService.java) | `SqlAudiencePreviewService:49` | `SqlAudiencePreviewService.preview:61`、`SqlAudiencePreviewService.previewUserIds:118`、`SqlAudiencePreviewService.evaluateConditions:130`、`SqlAudiencePreviewService.evalOne:150`、`SqlAudiencePreviewService.compare:198`、`SqlAudiencePreviewService.toDouble:211`、`SqlAudiencePreviewService.emptyResult:218` | `userProfileMapper.selectList`、`userTagMapper.selectList`、`behaviorSummaryMapper.selectList`、`latest.putIfAbsent` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateService.java) | `RealtimeProfileUpdateService:29` | `RealtimeProfileUpdateService.update:94`、`RealtimeProfileUpdateService.getProperties:128`、`RealtimeProfileUpdateService.normalizeTime:138`、`RealtimeProfileUpdateService.parseEventTime:152`、`RealtimeProfileUpdateService.toLong:158` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignDslValidator.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignDslValidator.java) | `CampaignDslValidator:43` | `CampaignDslValidator.validate:67`、`CampaignDslValidator.validateAudience:136`、`CampaignDslValidator.validateCondition:159`、`CampaignDslValidator.validateValue:214`、`CampaignDslValidator.validateSchedule:287`、`CampaignDslValidator.validateFrequencyCap:329`、`CampaignDslValidator.isValidChannel:339`、`CampaignDslValidator.parseOffsetDateTime:347`、`CampaignDslValidator.isParsableInteger:356`、`CampaignDslValidator.isParsableDecimal:366`、`CampaignDslValidator.toLong:376`、`CampaignDslValidator.toBigDecimal:382` | — |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignFieldRegistry.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/CampaignFieldRegistry.java) | `CampaignFieldRegistry:24`、`CampaignFieldRegistry.FieldDescriptor:217` | `CampaignFieldRegistry.init:31`、`CampaignFieldRegistry.register:183`、`CampaignFieldRegistry.get:188`、`CampaignFieldRegistry.contains:193`、`CampaignFieldRegistry.isEnabled:198`、`CampaignFieldRegistry.enabledFields:204`、`CampaignFieldRegistry.enabledFieldCodes:209` | `fields.put` |
| [pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/DslToRuleConverter.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/main/java/com/pulseflow/campaign/validation/DslToRuleConverter.java) | `DslToRuleConverter:34`、`DslToRuleConverter.ConvertedRule:111` | `DslToRuleConverter.convert:49`、`DslToRuleConverter.toBoolean:91`、`DslToRuleConverter.toThreshold:97` | `ruleConfig.put` |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/config/MyMetaObjectHandler.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/config/MyMetaObjectHandler.java) | `MyMetaObjectHandler:15` | `MyMetaObjectHandler.insertFill:19`、`MyMetaObjectHandler.updateFill:26` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/DecisionResult.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/DecisionResult.java) | `DecisionResult:11` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/DeliveryTaskDto.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/DeliveryTaskDto.java) | `DeliveryTaskDto:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/EventRequest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/EventRequest.java) | `EventRequest:14` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/EventResponse.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/EventResponse.java) | `EventResponse:7` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/FrequencyCheckResult.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/dto/FrequencyCheckResult.java) | `FrequencyCheckResult:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/AttributionModel.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/AttributionModel.java) | `AttributionModel:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/AttributionTaskStatus.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/AttributionTaskStatus.java) | `AttributionTaskStatus:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CampaignStatus.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CampaignStatus.java) | `CampaignStatus:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/ChannelType.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/ChannelType.java) | `ChannelType:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CompensationStatus.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CompensationStatus.java) | `CompensationStatus:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CompensationTaskType.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/CompensationTaskType.java) | `CompensationTaskType:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/DispatchStatus.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/DispatchStatus.java) | `DispatchStatus:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/EventType.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/EventType.java) | `EventType:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/ExecutionStatus.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/ExecutionStatus.java) | `ExecutionStatus:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/RuleType.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/RuleType.java) | `RuleType:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/TaskStatus.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/TaskStatus.java) | `TaskStatus:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/TriggerType.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/enums/TriggerType.java) | `TriggerType:4` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/exception/DuplicateEventException.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/exception/DuplicateEventException.java) | `DuplicateEventException:9` | `DuplicateEventException.constructor:14`、`DuplicateEventException.getEventId:20` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/exception/PulseFlowException.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/exception/PulseFlowException.java) | `PulseFlowException:9` | `PulseFlowException.constructor:14`、`PulseFlowException.constructor:20`、`PulseFlowException.getErrorCode:26` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/model/ApiResponse.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/model/ApiResponse.java) | `ApiResponse:14` | `ApiResponse.success:28`、`ApiResponse.success:37`、`ApiResponse.fail:46`、`ApiResponse.fail:55` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/util/DedupKeyUtil.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/util/DedupKeyUtil.java) | `DedupKeyUtil:15` | `DedupKeyUtil.constructor:18`、`DedupKeyUtil.forEvent:27`、`DedupKeyUtil.forDelayed:36`、`DedupKeyUtil.forScheduled:45` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/util/DelayedTaskConstants.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/util/DelayedTaskConstants.java) | `DelayedTaskConstants:10` | `DelayedTaskConstants.constructor:16` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/util/JsonUtil.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/common/util/JsonUtil.java) | `JsonUtil:12` | `JsonUtil.constructor:18`、`JsonUtil.toJson:29`、`JsonUtil.fromJson:44` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/AttributionRecord.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/AttributionRecord.java) | `AttributionRecord:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/AttributionTask.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/AttributionTask.java) | `AttributionTask:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/Campaign.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/Campaign.java) | `Campaign:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/CampaignExecution.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/CampaignExecution.java) | `CampaignExecution:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/CampaignRule.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/CampaignRule.java) | `CampaignRule:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/ClickEvent.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/ClickEvent.java) | `ClickEvent:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DataCompensationTask.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DataCompensationTask.java) | `DataCompensationTask:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryRecord.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryRecord.java) | `DeliveryRecord:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryTask.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/DeliveryTask.java) | `DeliveryTask:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/InAppMessage.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/InAppMessage.java) | `InAppMessage:20` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/PushRecord.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/PushRecord.java) | `PushRecord:19` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserBehaviorSummary.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserBehaviorSummary.java) | `UserBehaviorSummary:15` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserEvent.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserEvent.java) | `UserEvent:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserMetricDaily.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserMetricDaily.java) | `UserMetricDaily:18` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserMetricHourly.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserMetricHourly.java) | `UserMetricHourly:17` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserProfile.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserProfile.java) | `UserProfile:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserTag.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/entity/UserTag.java) | `UserTag:16` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionRecordMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionRecordMapper.java) | `AttributionRecordMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionTaskMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/AttributionTaskMapper.java) | `AttributionTaskMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignExecutionMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignExecutionMapper.java) | `CampaignExecutionMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignMapper.java) | `CampaignMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignRuleMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/CampaignRuleMapper.java) | `CampaignRuleMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/ClickEventMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/ClickEventMapper.java) | `ClickEventMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DataCompensationTaskMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DataCompensationTaskMapper.java) | `DataCompensationTaskMapper:13` | `DataCompensationTaskMapper.upsertPendingRestore:27`、`DataCompensationTaskMapper.selectOneDueUnderRetryForUpdate:49`、`DataCompensationTaskMapper.markProcessing:60` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryRecordMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryRecordMapper.java) | `DeliveryRecordMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryTaskMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/DeliveryTaskMapper.java) | `DeliveryTaskMapper:11` | `DeliveryTaskMapper.tryClaim:16` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/InAppMessageMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/InAppMessageMapper.java) | `InAppMessageMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/PushRecordMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/PushRecordMapper.java) | `PushRecordMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserBehaviorSummaryMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserBehaviorSummaryMapper.java) | `UserBehaviorSummaryMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserEventMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserEventMapper.java) | `UserEventMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserMetricDailyMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserMetricDailyMapper.java) | `UserMetricDailyMapper:14` | `UserMetricDailyMapper.selectActiveUserIdsSince:22` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserMetricHourlyMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserMetricHourlyMapper.java) | `UserMetricHourlyMapper:14` | `UserMetricHourlyMapper.upsertAccumulate:25` | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserProfileMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserProfileMapper.java) | `UserProfileMapper:9` | — | — |
| [pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserTagMapper.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/src/main/java/com/pulseflow/mapper/UserTagMapper.java) | `UserTagMapper:9` | — | — |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/config/KafkaConsumerConfig.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/config/KafkaConsumerConfig.java) | `KafkaConsumerConfig:17` | `KafkaConsumerConfig.kafkaListenerContainerFactory:24` | — |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/consumer/EventConsumer.java) | `EventConsumer:41`、`EventConsumer.(anonymous):181` | `EventConsumer.consume:59`、`EventConsumer.executePhase2:132`、`EventConsumer.executePhase3:142`、`EventConsumer.writeCompensationTask:159`、`EventConsumer.parseEvent:178` | `eventPersistenceService.persist`、`realtimeProfileUpdateService.update`、`decisionEngine.evaluate` |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/controller/EventController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/controller/EventController.java) | `EventController:16` | `EventController.acceptEvent:25` | — |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventPersistenceService.java) | `EventPersistenceService:40`、`EventPersistenceService.(anonymous):167`、`EventPersistenceService.PersistResult:213` | `EventPersistenceService.persist:60`、`EventPersistenceService.upsertMetricHourlyAtomic:93`、`EventPersistenceService.buildContextFromEvent:124`、`EventPersistenceService.buildUserEvent:136`、`EventPersistenceService.getProperties:151`、`EventPersistenceService.parseProperties:161`、`EventPersistenceService.propsToJson:174`、`EventPersistenceService.parseDateTime:184`、`EventPersistenceService.toLong:194`、`EventPersistenceService.toNullableLong:201`、`PersistResult.constructor:219`、`PersistResult.ok:226`、`PersistResult.fail:231`、`PersistResult.isOk:236`、`PersistResult.getEvent:238`、`PersistResult.getContext:240` | `userEventMapper.insert`、`userEventMapper.selectOne`、`ctx.put` |
| [pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/main/java/com/pulseflow/event/service/EventService.java) | `EventService:23` | `EventService.acceptEvent:44`、`EventService.buildEventPayload:79`、`EventService.sendToKafka:104` | `payload.put`、`kafkaTemplate.send` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignPerformanceSummaryJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignPerformanceSummaryJob.java) | `CampaignPerformanceSummaryJob:18` | `CampaignPerformanceSummaryJob.execute:26` | `campaignMapper.selectList`、`calculator.compute` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignSelectionJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CampaignSelectionJob.java) | `CampaignSelectionJob:25` | `CampaignSelectionJob.execute:36`、`CampaignSelectionJob.recoverStuckExecutions:80`、`CampaignSelectionJob.createExecutionInstance:102`、`CampaignSelectionJob.computeNextTriggerAt:145`、`CampaignSelectionJob.executeCampaign:168` | `campaignMapper.selectList`、`campaignExecutionMapper.selectList`、`campaignExecutionMapper.update`、`campaignMapper.update`、`campaignExecutionMapper.insert` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CompensationJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/CompensationJob.java) | `CompensationJob:42`、`CompensationJob.(anonymous):138` | `CompensationJob.execute:57`、`CompensationJob.markDone:99`、`CompensationJob.handleFailure:108`、`CompensationJob.parseEvent:136` | `jdbcTemplate.update`、`compensationClaimService.claimOne`、`realtimeProfileUpdateService.update`、`decisionEngine.evaluate`、`compensationTaskMapper.update` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DailyMetricJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DailyMetricJob.java) | `DailyMetricJob:11` | `DailyMetricJob.execute:19` | `jdbcTemplate.update` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DataCleanupJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DataCleanupJob.java) | `DataCleanupJob:12` | `DataCleanupJob.execute:20` | `jdbcTemplate.update` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DelayTaskRecoveryJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DelayTaskRecoveryJob.java) | `DelayTaskRecoveryJob:13` | `DelayTaskRecoveryJob.execute:21` | `delayedTaskManager.recoverStuckTasks` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DispatchRetryJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/DispatchRetryJob.java) | `DispatchRetryJob:20` | `DispatchRetryJob.execute:30` | `deliveryTaskMapper.selectList`、`kafkaTemplate.send(DELIVERY_TOPIC, key, payload).get`、`kafkaTemplate.send`、`deliveryTaskMapper.update` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/RetryCompensationJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/RetryCompensationJob.java) | `RetryCompensationJob:18` | `RetryCompensationJob.execute:27` | `deliveryTaskMapper.update`、`compensationTaskMapper.update` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/TagRecalcJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/TagRecalcJob.java) | `TagRecalcJob:19` | `TagRecalcJob.execute:29` | `metricDailyMapper.selectActiveUserIdsSince`、`metricDailyMapper.selectList`、`strategy.compute`、`userTagMapper.insert` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/WindowMetricJob.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/handler/WindowMetricJob.java) | `WindowMetricJob:29` | `WindowMetricJob.execute:42`、`WindowMetricJob.processUser:71`、`WindowMetricJob.computeAndSave:115`、`WindowMetricJob.sumHourlyEventCount:130`、`WindowMetricJob.sumDailyEventCount:138`、`WindowMetricJob.sumDailyAmount:146` | `metricDailyMapper.selectActiveUserIdsSince`、`metricDailyMapper.selectList`、`metricHourlyMapper.selectList`、`behaviorSummaryMapper.insert` |
| [pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/service/CompensationClaimService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/src/main/java/com/pulseflow/job/service/CompensationClaimService.java) | `CompensationClaimService:24` | `CompensationClaimService.claimOne:37` | `compensationTaskMapper.selectOneDueUnderRetryForUpdate` |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/ProfileService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/ProfileService.java) | `ProfileService:23` | `ProfileService.getRealtimeMetrics:37`、`ProfileService.getWindowMetrics:67`、`ProfileService.getUserTags:103`、`ProfileService.getMetricValue:115`、`ProfileService.hasTag:134` | `result.putAll`、`result.put`、`behaviorSummaryMapper.selectList`、`result.putIfAbsent`、`cacheMap.putAll` |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/UserPreferenceService.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/service/UserPreferenceService.java) | `UserPreferenceService:29` | `UserPreferenceService.canDeliver:45`、`UserPreferenceService.isDnd:62`、`UserPreferenceService.setDnd:68`、`UserPreferenceService.isUnsubscribed:78`、`UserPreferenceService.setUnsubscribed:84`、`UserPreferenceService.isAlreadyConverted:94` | `attributionRecordMapper.selectCount` |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/ActiveUserTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/ActiveUserTagStrategy.java) | `ActiveUserTagStrategy:13` | `ActiveUserTagStrategy.getTagName:17`、`ActiveUserTagStrategy.compute:23` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/AiPreferenceTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/AiPreferenceTagStrategy.java) | `AiPreferenceTagStrategy:13` | `AiPreferenceTagStrategy.getTagName:17`、`AiPreferenceTagStrategy.compute:23` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/BargainHunterTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/BargainHunterTagStrategy.java) | `BargainHunterTagStrategy:13` | `BargainHunterTagStrategy.getTagName:17`、`BargainHunterTagStrategy.compute:23` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/ChurnRiskTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/ChurnRiskTagStrategy.java) | `ChurnRiskTagStrategy:13` | `ChurnRiskTagStrategy.getTagName:17`、`ChurnRiskTagStrategy.compute:23` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/HighValueTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/HighValueTagStrategy.java) | `HighValueTagStrategy:14` | `HighValueTagStrategy.getTagName:18`、`HighValueTagStrategy.compute:24` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/LoyalCustomerTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/LoyalCustomerTagStrategy.java) | `LoyalCustomerTagStrategy:13` | `LoyalCustomerTagStrategy.getTagName:17`、`LoyalCustomerTagStrategy.compute:23` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/NewUserTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/NewUserTagStrategy.java) | `NewUserTagStrategy:13` | `NewUserTagStrategy.getTagName:17`、`NewUserTagStrategy.compute:23` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/PriceSensitiveTagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/PriceSensitiveTagStrategy.java) | `PriceSensitiveTagStrategy:14` | `PriceSensitiveTagStrategy.getTagName:18`、`PriceSensitiveTagStrategy.compute:24` | — |
| [pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/TagStrategy.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/src/main/java/com/pulseflow/profile/tag/strategy/TagStrategy.java) | `TagStrategy:14` | `TagStrategy.getTagName:19`、`TagStrategy.compute:24`、`TagStrategy.defaultValue:29` | — |
| [pulseflow/pulseflow-simulator/src/main/java/com/pulseflow/simulator/controller/SimulatorController.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-simulator/src/main/java/com/pulseflow/simulator/controller/SimulatorController.java) | `SimulatorController:18` | `SimulatorController.generateEvent:32`、`SimulatorController.simulateUserJourney:64`、`SimulatorController.generateBulk:96`、`SimulatorController.sendEvent:118` | `props.put`、`baseProps.put`、`orderProps.put` |

### I.3 Web全部TypeScript/Vue脚本

| 文件 | 类型/结构声明 | 命名函数:行号 | 关键调用表达式（选摘） |
|---|---|---|---|
| [pulseflow-web/src/api/attribution.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/attribution.ts) | — | `listAttributions:6`、`getAttribution:9` | — |
| [pulseflow-web/src/api/auth.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/auth.ts) | — | `login:5`、`me:19`、`logout:28` | — |
| [pulseflow-web/src/api/campaigns.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/campaigns.ts) | `CampaignListQuery:13` | `listCampaigns:22`、`getCampaign:25`、`getCampaignPerformance:28`、`getCampaignDeliveryTrend:31`、`getCampaignDeliveries:35`、`getCampaignAttributions:38` | — |
| [pulseflow-web/src/api/dashboard.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/dashboard.ts) | — | `getDashboardSummary:6`、`getDashboardTrends:9` | — |
| [pulseflow-web/src/api/deliveries.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/deliveries.ts) | — | `listDeliveries:6`、`getDelivery:9` | — |
| [pulseflow-web/src/api/demo.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/demo.ts) | — | `at:21`、`demoResolve:24`、`detailFor:35`、`page:90`、`dashboardSummary:100`、`dashboardTrends:102`、`campaigns:108`、`campaignDetail:113`、`performance:115`、`campaignTrend:120`、`users:125`、`userDetail:127`、`events:132`、`event:134`、`deliveries:136`、`delivery:138`、`attributions:140`、`attribution:142`、`system:144` | — |
| [pulseflow-web/src/api/events.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/events.ts) | — | `listEvents:6`、`getEvent:9` | — |
| [pulseflow-web/src/api/http.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/http.ts) | `ApiError:35` | `unwrapApiResponse:44`、`apiRequest:52`、`setToken:61` | `axios.create`、`http.interceptors.request.use`、`http.request` |
| [pulseflow-web/src/api/investigations.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/investigations.ts) | — | `request:9`、`startInvestigation:14`、`getInvestigation:25`、`followUpInvestigation:33`、`cancelInvestigation:44`、`proposeCampaign:46`、`createDraft:48`、`getDraft:50`、`refreshDraft:52`、`confirmDraft:54`、`watchInvestigation:57` | `http.request` |
| [pulseflow-web/src/api/system.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/system.ts) | — | `getSystemStatus:6` | — |
| [pulseflow-web/src/api/users.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/api/users.ts) | — | `listUsers:6`、`getUser:9`、`getUserProfile:12`、`getUserEvents:15` | — |
| [pulseflow-web/src/App.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/App.vue) | — | — | — |
| [pulseflow-web/src/components/JsonDrawer.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/components/JsonDrawer.vue) | — | — | — |
| [pulseflow-web/src/components/KpiCard.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/components/KpiCard.vue) | — | — | — |
| [pulseflow-web/src/components/StatusTag.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/components/StatusTag.vue) | — | — | — |
| [pulseflow-web/src/components/TrendChart.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/components/TrendChart.vue) | — | `render:24`、`valueFormatter:36`、`formatter:53`、`compactNumber:69`、`resize:76` | — |
| [pulseflow-web/src/env.d.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/env.d.ts) | `ImportMetaEnv:4`、`ImportMeta:9` | — | — |
| [pulseflow-web/src/layouts/AppShell.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/layouts/AppShell.vue) | — | — | — |
| [pulseflow-web/src/main.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/main.ts) | — | — | — |
| [pulseflow-web/src/router/index.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/router/index.ts) | — | `component:8`、`component:12`、`component:15`、`component:16`、`component:17`、`component:18`、`component:19`、`component:20`、`component:21`、`component:22`、`component:23`、`component:24` | — |
| [pulseflow-web/src/stores/auth.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/stores/auth.ts) | — | `login:15`、`restore:21`、`logout:38` | — |
| [pulseflow-web/src/types/api.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/types/api.ts) | `ApiEnvelope:3`、`PageResponse:10`、`DashboardSummary:19`、`TrendPoint:31`、`DashboardTrends:37`、`CampaignListItem:44`、`CampaignView:59`、`RuleView:81`、`AudienceView:91`、`DeliverySummary:99`、`AttributionSummary:110`、`CampaignDetail:117`、`PerformanceView:126`、`UserListItem:142`、`UserProfileView:154`、`EventView:164`、`UserDetail:178`、`DeliveryListItem:189`、`DeliveryRecordView:204`、`ClickView:216`、`AttributionView:226`、`DeliveryDetail:240`、`SystemStatus:248`、`AuthSession:256` | — | — |
| [pulseflow-web/src/types/investigation.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/types/investigation.ts) | `Diagnosis:3`、`Evidence:13`、`Hypothesis:19`、`PromotionFact:24`、`CampaignDsl:26`、`CampaignDraft:34`、`Investigation:39` | — | — |
| [pulseflow-web/src/utils/format.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/utils/format.ts) | — | `formatNumber:2`、`formatCurrency:11`、`formatPercent:21`、`formatDateTime:27`、`formatDate:40` | — |
| [pulseflow-web/src/utils/status.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/utils/status.ts) | — | `statusTone:23`、`getStatusLabel:49` | — |
| [pulseflow-web/src/views/Attribution.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Attribution.vue) | — | `load:16`、`filter:18`、`changePage:20` | — |
| [pulseflow-web/src/views/CampaignDetail.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/CampaignDetail.vue) | — | `load:21`、`configValue:27` | — |
| [pulseflow-web/src/views/Campaigns.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Campaigns.vue) | — | `load:20`、`filter:32`、`changePage:34`、`openCampaign:36`、`openCampaignRow:38` | — |
| [pulseflow-web/src/views/Dashboard.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Dashboard.vue) | — | `load:34` | — |
| [pulseflow-web/src/views/Deliveries.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Deliveries.vue) | — | `load:23`、`filter:25`、`openDelivery:27`、`openDeliveryRow:29`、`changePage:31` | — |
| [pulseflow-web/src/views/Events.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Events.vue) | — | `load:21`、`filter:26`、`openEvent:28`、`changePage:30` | — |
| [pulseflow-web/src/views/Investigations.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Investigations.vue) | — | `evidenceLabel:31`、`stopStream:33`、`reportError:35`、`refresh:40`、`connect:53`、`changed:61`、`load:70`、`submit:89`、`cancel:104`、`improve:112`、`propose:117`、`generateDraft:129`、`review:141`、`rePreview:148`、`confirm:156` | — |
| [pulseflow-web/src/views/Login.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Login.vue) | — | `submit:16` | — |
| [pulseflow-web/src/views/System.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/System.vue) | — | `tone:18`、`load:20` | — |
| [pulseflow-web/src/views/UserDetail.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/UserDetail.vue) | — | `load:25`、`openEvent:31`、`eventTime:33` | — |
| [pulseflow-web/src/views/Users.vue](C:/workbuddy-workspace/PulseFlow/pulseflow-web/src/views/Users.vue) | — | `load:18`、`filter:23`、`openUser:25`、`openUserRow:27`、`changePage:29` | — |

### I.4 测试与工程覆盖清单

以下文件均已阅读实现/断言，是否实际执行以A.4/G.3为准；仅列目录或测试名不代表测试已运行。

**Python测试（18）**

- [pulseflow-agent/tests/__init__.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/__init__.py)
- [pulseflow-agent/tests/agent/test_azure_privacy.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/agent/test_azure_privacy.py)
- [pulseflow-agent/tests/agent/test_remaining_tools.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/agent/test_remaining_tools.py)
- [pulseflow-agent/tests/agent/test_safety.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/agent/test_safety.py)
- [pulseflow-agent/tests/agent/test_trajectories.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/agent/test_trajectories.py)
- [pulseflow-agent/tests/conftest.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/conftest.py)
- [pulseflow-agent/tests/evals/__init__.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/evals/__init__.py)
- [pulseflow-agent/tests/evals/harness.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/evals/harness.py)
- [pulseflow-agent/tests/evals/test_dataset.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/evals/test_dataset.py)
- [pulseflow-agent/tests/observability/test_tracing.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/observability/test_tracing.py)
- [pulseflow-agent/tests/proposals/test_proposal.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/proposals/test_proposal.py)
- [pulseflow-agent/tests/proposals/test_proposal_migration.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/proposals/test_proposal_migration.py)
- [pulseflow-agent/tests/test_foundation.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/test_foundation.py)
- [pulseflow-agent/tests/test_runtime.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/test_runtime.py)
- [pulseflow-agent/tests/tools/test_pulseflow_api.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/tools/test_pulseflow_api.py)
- [pulseflow-agent/tests/workspace/test_followup.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/workspace/test_followup.py)
- [pulseflow-agent/tests/workspace/test_mysql_integration.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/workspace/test_mysql_integration.py)
- [pulseflow-agent/tests/workspace/test_repository.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/tests/workspace/test_repository.py)

**Java测试/IT（30）**

- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentGatewaySecurityTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentGatewaySecurityTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricQueryIT.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricQueryIT.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentMetricServiceTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentProposalGatewayTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentProposalGatewayTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentTelemetryMysqlIT.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentTelemetryMysqlIT.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentTelemetryTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentTelemetryTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentToolAuthTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentToolAuthTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentToolServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/AgentToolServiceTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/CampaignProposalDraftServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/agenttools/CampaignProposalDraftServiceTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/BusinessRuntimeBootstrapIT.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/BusinessRuntimeBootstrapIT.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/config/LocalAuthSessionConfigTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/config/LocalAuthSessionConfigTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MapperRegistrationTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/MapperRegistrationTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/AuthControllerTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/AuthControllerTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/LocalAuthPropertiesTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/LocalAuthPropertiesTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryControllerTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryControllerTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/boot/web/WebQueryServiceTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/common/util/DedupKeyUtilTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/common/util/DedupKeyUtilTest.java)
- [pulseflow/pulseflow-boot/src/test/java/com/pulseflow/event/service/EventIdempotentConsumptionIT.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/test/java/com/pulseflow/event/service/EventIdempotentConsumptionIT.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculatorTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/analytics/PerformanceSummaryCalculatorTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionServiceTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionTaskConsumerTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/attribution/AttributionTaskConsumerTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/content/CampaignContentValidatorTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/content/CampaignContentValidatorTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/decision/DecisionEngineTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/decision/DecisionEngineTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/draft/CampaignDraftServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/draft/CampaignDraftServiceTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/preview/SqlAudiencePreviewServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/preview/SqlAudiencePreviewServiceTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/profile/RealtimeProfileUpdateServiceTest.java)
- [pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/validation/CampaignDslValidatorTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/src/test/java/com/pulseflow/campaign/validation/CampaignDslValidatorTest.java)
- [pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/consumer/EventConsumerTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/consumer/EventConsumerTest.java)
- [pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventPersistenceServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventPersistenceServiceTest.java)
- [pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventServiceTest.java](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/src/test/java/com/pulseflow/event/service/EventServiceTest.java)

**Web测试/E2E（5）**

- [pulseflow-web/e2e/core.spec.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/e2e/core.spec.ts)
- [pulseflow-web/e2e/integration/real-backend.spec.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/e2e/integration/real-backend.spec.ts)
- [pulseflow-web/tests/http.test.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/tests/http.test.ts)
- [pulseflow-web/tests/investigations.test.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/tests/investigations.test.ts)
- [pulseflow-web/tests/utils.test.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/tests/utils.test.ts)

**工程脚本（13）**

- [testing/agent_smoke.py](C:/workbuddy-workspace/PulseFlow/testing/agent_smoke.py)
- [testing/common.ps1](C:/workbuddy-workspace/PulseFlow/testing/common.ps1)
- [testing/functional/generate.py](C:/workbuddy-workspace/PulseFlow/testing/functional/generate.py)
- [testing/functional/replay.py](C:/workbuddy-workspace/PulseFlow/testing/functional/replay.py)
- [testing/functional/run.ps1](C:/workbuddy-workspace/PulseFlow/testing/functional/run.ps1)
- [testing/functional/state.py](C:/workbuddy-workspace/PulseFlow/testing/functional/state.py)
- [testing/functional/test_state.py](C:/workbuddy-workspace/PulseFlow/testing/functional/test_state.py)
- [testing/functional/test_validate.py](C:/workbuddy-workspace/PulseFlow/testing/functional/test_validate.py)
- [testing/functional/validate.py](C:/workbuddy-workspace/PulseFlow/testing/functional/validate.py)
- [testing/performance/event.js](C:/workbuddy-workspace/PulseFlow/testing/performance/event.js)
- [testing/performance/performance.js](C:/workbuddy-workspace/PulseFlow/testing/performance/performance.js)
- [testing/performance/run.ps1](C:/workbuddy-workspace/PulseFlow/testing/performance/run.ps1)
- [testing/run-all.ps1](C:/workbuddy-workspace/PulseFlow/testing/run-all.ps1)

**配置/迁移/部署（38）**

- [.github/workflows/ci.yml](C:/workbuddy-workspace/PulseFlow/.github/workflows/ci.yml)
- [pulseflow/Dockerfile](C:/workbuddy-workspace/PulseFlow/pulseflow/Dockerfile)
- [pulseflow/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pom.xml)
- [pulseflow/pulseflow-boot/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/pom.xml)
- [pulseflow/pulseflow-boot/src/main/resources/application.yml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/application.yml)
- [pulseflow/pulseflow-boot/src/main/resources/db/migration/V1__init.sql](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V1__init.sql)
- [pulseflow/pulseflow-boot/src/main/resources/db/migration/V2__channel_tables.sql](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V2__channel_tables.sql)
- [pulseflow/pulseflow-boot/src/main/resources/db/migration/V3__ai_campaign_tables.sql](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V3__ai_campaign_tables.sql)
- [pulseflow/pulseflow-boot/src/main/resources/db/migration/V4__ai_review_state_machine.sql](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V4__ai_review_state_machine.sql)
- [pulseflow/pulseflow-boot/src/main/resources/db/migration/V5__review_status_split_and_ownership.sql](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V5__review_status_split_and_ownership.sql)
- [pulseflow/pulseflow-boot/src/main/resources/db/migration/V6__agent_draft_grants.sql](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V6__agent_draft_grants.sql)
- [pulseflow/pulseflow-boot/src/main/resources/db/migration/V7__proposal_draft_boundary.sql](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-boot/src/main/resources/db/migration/V7__proposal_draft_boundary.sql)
- [pulseflow/pulseflow-campaign/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-campaign/pom.xml)
- [pulseflow/pulseflow-common/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-common/pom.xml)
- [pulseflow/pulseflow-event/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-event/pom.xml)
- [pulseflow/pulseflow-job/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-job/pom.xml)
- [pulseflow/pulseflow-profile/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-profile/pom.xml)
- [pulseflow/pulseflow-simulator/pom.xml](C:/workbuddy-workspace/PulseFlow/pulseflow/pulseflow-simulator/pom.xml)
- [pulseflow-agent/.env.example](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/.env.example)
- [pulseflow-agent/alembic.ini](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/alembic.ini)
- [pulseflow-agent/Dockerfile](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/Dockerfile)
- [pulseflow-agent/migrations/env.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/env.py)
- [pulseflow-agent/migrations/versions/0001_agent_workspace.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/versions/0001_agent_workspace.py)
- [pulseflow-agent/migrations/versions/0002_campaign_proposals.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/versions/0002_campaign_proposals.py)
- [pulseflow-agent/migrations/versions/0003_proposal_boundary.py](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/migrations/versions/0003_proposal_boundary.py)
- [pulseflow-agent/pyproject.toml](C:/workbuddy-workspace/PulseFlow/pulseflow-agent/pyproject.toml)
- [pulseflow-web/eslint.config.js](C:/workbuddy-workspace/PulseFlow/pulseflow-web/eslint.config.js)
- [pulseflow-web/package.json](C:/workbuddy-workspace/PulseFlow/pulseflow-web/package.json)
- [pulseflow-web/playwright.config.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/playwright.config.ts)
- [pulseflow-web/playwright.integration.config.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/playwright.integration.config.ts)
- [pulseflow-web/tsconfig.json](C:/workbuddy-workspace/PulseFlow/pulseflow-web/tsconfig.json)
- [pulseflow-web/vite.config.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/vite.config.ts)
- [pulseflow-web/vitest.config.ts](C:/workbuddy-workspace/PulseFlow/pulseflow-web/vitest.config.ts)
- [testing/.env.test.example](C:/workbuddy-workspace/PulseFlow/testing/.env.test.example)
- [testing/data/fixtures/seed-demo-data.sql](C:/workbuddy-workspace/PulseFlow/testing/data/fixtures/seed-demo-data.sql)
- [testing/docker-compose.test.yml](C:/workbuddy-workspace/PulseFlow/testing/docker-compose.test.yml)
- [testing/functional/campaign-fixture.sql](C:/workbuddy-workspace/PulseFlow/testing/functional/campaign-fixture.sql)
- [testing/functional/ownership.json](C:/workbuddy-workspace/PulseFlow/testing/functional/ownership.json)

CSS/HTML、公开README和架构说明也已检查职责与部署约定；没有执行视觉像素/浏览器布局审查。固定JSON fixture/评测输入按契约与测试使用检查，未把运行时生成数据逐行当生产代码审计。

## 最终文件保护与Git复核

最终复核：分支 `codex/simplify-agent-proposal-boundary`，HEAD `40febc966fca791e67445441a7eaca8cf60b4d10` 未变。原有 355 个受保护非缓存文件与报告保存前快照比较，变更数量 **0**；全部报告源码链接及引用行号均有效，A–H 章节齐全，42 个审计编号唯一，数量分布与 H.1 一致。

当前 Git 状态：290 个原有修改项、17 个原有删除项、5 个折叠后的未跟踪项；未跟踪项由原有4项加本轮 `docs/architecture/` 报告目录组成。**本轮仅新增本 Markdown，生产/测试源码修改0，未提交、未合并、未创建PR。** Java/Mypy/Pytest 等生成的 ignored 构建/缓存/系统临时验证文件不计为源码变更；未清理用户原有工作材料。`git diff --check` 通过。
