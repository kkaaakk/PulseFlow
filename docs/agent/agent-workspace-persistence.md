# Agent Workspace 持久化（Phase 4）

调查现在是独立的业务对象，而非一次模型调用。`InvestigationRepository` 接口隔离存储实现；SQLAlchemy 异步实现只访问独立 Agent schema 的表。没有 Pydantic AI Memory、Harness 或业务数据库直连。

## 状态与记录

| 表 | 内容 |
|---|---|
| `agent_investigation` | ID、Goal、`RUNNING / COMPLETED / INSUFFICIENT_EVIDENCE / BUDGET_EXHAUSTED / FAILED / CANCELLED`、当前 Scope 与版本、最终 Diagnosis、Tool 轨迹、时间戳 |
| `agent_evidence` | Java Tool 生成的 Evidence、Java queryId、scope 版本；无原始用户行 |
| `agent_hypothesis` | 陈述、`OPEN / SUPPORTED / WEAKENED / REJECTED`、支持/反驳 Evidence ID、文字原因与 low/medium/high 置信度 |
| `agent_message` | 用户问题和 Agent 可见回复；Agent 回复可附对应 Diagnosis JSON。不保存隐藏推理或完整模型消息序列。 |
| `agent_campaign_proposal`（Phase 6） | Evidence 支持的 Proposal 与 Java Draft 引用；不保存草稿授权秘密。 |

Evidence 仍只能由 Java Tool 响应自动生成。Agent 本地 Tool `propose_hypothesis`、`update_hypothesis`、`list_hypotheses` 只读写 Workspace；`update_scope` 更新当前调查范围。Hypothesis 引用必须是当前 Scope 的既有 Evidence ID，不能手工创建 Evidence。

续查复用同一 Investigation ID。Scope 变化时旧 Evidence 保留为历史背景，新 Diagnosis 只能引用当前 Scope 版本的 Evidence；旧 Hypothesis 标记 `REJECTED`，原因为 `superseded_by_scope_change`。这避免把总体指标直接当作新细分人群的结论。旧 Diagnosis 保存在先前的 Assistant 消息中。对于 Java 还不支持的细分指标，Agent 可以返回 `INSUFFICIENT_EVIDENCE` 并列出缺口。

## 内部 API

所有路径仍使用 `X-PulseFlow-Agent-Token`，浏览器不能直接访问：

| 路径 | 用途 |
|---|---|
| `POST /internal/v1/investigations` | 创建并运行调查，body 为 `{"question":"..."}` |
| `GET /internal/v1/investigations/{id}` | 读取完整调查、Evidence、Hypothesis、可见消息和最终 Diagnosis |
| `POST /internal/v1/investigations/{id}/follow-up` | 同 ID 续查，body 为 `{"question":"...","scope":"可选范围"}`；也可由 Agent `update_scope` Tool 根据用户追问更新范围 |

请求与可见回复通过 PII 检查后才写入 Agent DB。DB 连接不可用或未迁移时 readiness 失败；健康检查不调用 LLM 或 Java。并发续查同一 RUNNING 调查返回冲突。调用任务取消会保存 `CANCELLED` 状态；尚无主动取消 API，运行中进程崩溃后的租约恢复也未实现。

## 数据库边界与迁移

生产环境必须单独创建数据库 `pulseflow_agent`，提供**仅有该 schema 权限**的非 root 账号。应用账号只需 `SELECT, INSERT, UPDATE, DELETE`；迁移账号另有建表/索引权限。两个账号都不得拥有 PulseFlow 业务 schema 的访问权。例如由 DBA 分别创建账号后执行：

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON pulseflow_agent.* TO 'pulseflow_agent_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX
  ON pulseflow_agent.* TO 'pulseflow_agent_migrate'@'%';
```

密码由部署密钥管理器提供，不写入仓库。迁移时用迁移账号设置 `PULSEFLOW_AGENT_DATABASE_URL=mysql+asyncmy://.../pulseflow_agent`，运行 `uv run alembic upgrade head`；运行服务时改用应用账号。生产配置拒绝非 MySQL URL、非 `pulseflow_agent` 数据库或 root 用户。实际 grants 仍须由部署方核验。

本地可使用 `.env.example` 中的 SQLite URL；先执行 Alembic 迁移。`PULSEFLOW_AGENT_ENV=test` 仅为离线测试自动建表。CI 使用 MySQL 8 临时 service 运行迁移与存取测试；普通测试无真实模型和 Azure 请求。
