# Campaign Proposal 与人工确认（Phase 6）

**Agent owns reasoning. Java owns deterministic business writes.**

Agent 调查并持久化 Evidence、Hypothesis、Diagnosis 和结构化 `CampaignProposal` 后停止。Agent 的 Java Tool API 只读，不提供 Draft、Confirm、Activate 或触达写接口。Proposal 持久化于独立 Agent schema 的 `agent_campaign_proposal`，关联 Investigation、Java 操作者、当前范围 Evidence、创建时间、状态和可选 Draft ID。

## 用户链路

1. 登录用户调用 `POST /api/investigations`。Java 使用 Sa-Token 会话登记 Investigation owner。
2. Diagnosis 完成后，用户调用 `POST /api/investigations/{id}/proposal`，提交方案问题与已批准的结构化 `promotionFacts`。Java 检查 Investigation owner，并通过内部令牌将会话 operator ID 和事实传给 Agent。Agent 检查模型产物仅引用当前范围 Evidence、优惠事实与用户提交内容完全一致，PII 守卫检查自由文本，然后持久化 Proposal，状态为 `GENERATED`。
3. UI 展示 Proposal。用户单独点击“生成 Campaign Draft”，调用 `POST /api/campaign-proposals/{proposalId}/draft`，请求不提交 Proposal 或 DSL。
4. Java 从 Sa-Token 获取用户；通过受内部令牌保护的 Agent Proposal Read API 读取持久化 Proposal；检查 Proposal owner、Investigation owner、`GENERATED` 状态及 Evidence 引用关系。Java 从已存 Proposal 转换 `CampaignDsl`，复用 `CampaignDslValidator` 和 `AudiencePreviewService`，再调用 `CampaignDraftService`。
5. 用户查看 Draft 后显式调用原有 `/api/campaign-drafts/{id}/confirm`。Java 再次检查归属与 DSL，创建状态为 `DRAFT` 的 Campaign 和 CampaignRule。Agent 不参与确认、激活或触达。

## 权威数据和幂等

浏览器不能在 Draft 创建时覆盖 `promotionFacts`。Agent 生成时必须与用户已批准的事实完全匹配；Java 只从数据库中的 Proposal 取得它们。未提供优惠事实时 Draft 可进入 `NEEDS_CONFIRMATION`，人工补齐且重新校验之前不可 Confirm。

Draft 使用 Proposal ID 作为 `campaign_ai_draft.request_id`。该列已有唯一约束。Java 事务锁定 Investigation owner 行、检查现有 Draft 并执行校验与创建；重复请求返回原 Draft。Draft 记录包含 Investigation ID，以便 Agent 暂时不可用时仍能核对两层归属并返回已提交 Draft。

Java Draft 事务提交后，Java 尝试把 Agent Proposal 标为 `DRAFT_CREATED` 并关联 Draft ID。若状态回写失败，Draft 仍有效；重试先查 Java Draft，再修复 Proposal 状态，不使用分布式事务。旧 Proposal 数据缺少可信 owner，迁移后标为 `CANCELLED`，需另行人工核对；它们不会自动用于新 Draft。

Java Flyway V6 是历史迁移，V7 删除旧短期授权表并为 Draft 增加 Investigation 关联。Agent Alembic 0003 增加 owner、状态、Draft ID 和更新时间。`PULSEFLOW_AGENT_INTERNAL_TOKEN` 继续保护 Agent ↔ Java 的内部只读 Tool API 与 Java ↔ Agent 的 Proposal API；它不是浏览器凭证。
