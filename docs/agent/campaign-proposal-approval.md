# Campaign Proposal 与人工确认（Phase 6）

调查和执行分离。Agent 可以提出方案、创建一个 Java 草稿；它没有确认、激活、发消息、发券或修改现有频控的 Tool。

## 三层权限

| 层级 | 能力 | 权威边界 |
|---|---|---|
| OBSERVE | 六个聚合查询 Tool | Java Internal Token，只读 |
| PROPOSE | `create_campaign_draft` | Internal Token **加** Java 登录会话签发的短时草稿授权 |
| EXECUTE | 人工确认、后续激活/触达 | 原有 Java Sa-Token 用户权限；Agent 不持有用户 Token |

草稿 Tool 只在有当前 Investigation 的 PROPOSE 授权、当前范围的真实 Evidence 且本次尚未创建草稿时向模型开放。支持 Evidence ID 必须存在于当前 Workspace。Java 草稿响应不是新的业务 Evidence；Proposal 与草稿引用保存在独立 `agent_campaign_proposal` 表。

## 用户链路

1. Java 登录用户调用 `POST /api/investigations`，body 为 `{"question":"..."}`。Java 转发给内部 Agent，按返回的新 Investigation ID 登记操作者归属。不能通过浏览器传 `operatorId` 建立归属。
2. Diagnosis 完成后，用户调用 `POST /api/investigations/{id}/proposal`，body 为 `{"question":"设计召回草稿","promotionFacts":[...]}`。Java 检查调查归属，从 Sa-Token 取得操作者，签发仅限该 Investigation 的十分钟授权。授权不返回浏览器。
3. Java 网关调用 Agent 的内部 `/internal/v1/investigations/{id}/proposal`。Agent 根据 Evidence 生成类型化 `CampaignProposal`，调用 Java `/internal/v1/agent-tools/campaign-drafts`。
4. Java 验证授权、范围、有效期和促销事实，然后将 Proposal 转为 `CampaignDsl`，复用 `CampaignDslValidator`、`AudiencePreviewService` 与 `CampaignDraftService`。只创建草稿，返回 `state=DRAFT`、验证状态、预估、版本和 `requiresHumanConfirmation=true`。
5. 用户使用原有 `/api/campaign-drafts/{id}` 查看/编辑草稿，并显式调用 `/api/campaign-drafts/{id}/confirm`。Java 再次检查操作者归属和 DSL 后创建 Campaign 与 CampaignRule。现有确认逻辑创建的 Campaign 仍是 DRAFT；激活/触达继续属于确定性 Java 用户流程。

旧的机器直建 Investigation 没有 Java 用户归属，不能通过公开 Proposal 网关领取授权。Phase 7 将补齐完整用户调查 UI 与网关。

## Proposal 与促销事实

Python `CampaignProposal` 包含活动名、目标、原因、目标人群、渠道、时间、频控、促销事实和 `supporting_evidence_ids`。支持引用必须来自当前范围，不能引用历史或伪造 Evidence。自由文本在调用 Java 和持久化前经过 PII 检查。

促销事实必须由 Java 操作者显式提供，并绑定在授权记录中；Python 和 Java 两侧都拒绝 Agent 添加或替换事实。未提供事实时允许生成 `NEEDS_CONFIRMATION` 草稿，人工补齐并重新校验前不能确认。Agent 不能把建议的折扣冒充已有优惠。

## 授权与存储

Java Flyway V6 新增 `agent_investigation_owner` 与 `agent_campaign_draft_grant`。授权使用 256 位随机秘密，数据库只保存 SHA-256 摘要；检查时使用 constant-time comparison。它绑定 Investigation、Java 操作者、到期时间和授权促销事实，不授予任何执行权限。

一份授权最多创建一个草稿。事务内锁定授权行；同一授权和相同 Proposal 的并发/重复请求返回同一 Draft，不同 Proposal 被拒绝。Java Internal Token 单独不能创建草稿；草稿授权也不能登录 Sa-Token 或调用确认入口。Token、授权秘密不进入模型参数、数据库 Proposal JSON、日志或导出 trace。

Agent Alembic `0002_campaign_proposals` 保存 Proposal、Java Draft 元数据、范围版本与时间戳。Java 与 Agent DB 的迁移分别执行；Python 用户仍只拥有独立 Agent schema 权限，不能读取 Java 授权或业务表。Java 设置 `PULSEFLOW_AGENT_SERVICE_URL` 指向内网 Agent 地址，两端共享 `PULSEFLOW_AGENT_INTERNAL_TOKEN`；不配置 URL 时 Agent 网关失败关闭，Java 核心继续运行。

## 验证与限制

测试覆盖 Evidence/Scope 校验、PII、促销事实来源、草稿持久化、授权有效期/错配/归属、并发幂等、机器凭证不能确认，以及 Agent Draft→人工 Confirm 的真实 Java 服务链。CI 使用 MySQL 验证 Flyway、授权行锁和 Agent Proposal 存储；普通 CI 不请求真实模型或 Azure。

两个数据库之间没有分布式事务。若 Java 已创建草稿而 Agent 持久化失败，可使用同一授权重试并取得同一草稿；新授权仍可能产生另一个草稿。跨授权去重、过期授权清理与 UI 恢复流程留给生产强化阶段。真实模型方案质量仍需可选真实 Eval 验证。
