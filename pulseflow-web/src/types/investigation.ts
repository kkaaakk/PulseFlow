// 调查、证据、假设、方案和草稿的前后端数据契约。
/** 最终诊断，包含发现、证据引用、未解决问题和置信度。 */
export interface Diagnosis {
  status: 'DIAGNOSED' | 'INSUFFICIENT_EVIDENCE'
  summary: string
  confidence: 'low' | 'medium' | 'high'
  findings: { claim: string; evidence_ids: string[] }[]
  evidence_ids: string[]
  unresolved_questions: string[]
  recommended_next_action: string | null
}
/** 来自 Java 聚合工具的可追溯证据，绑定查询、数据及范围版本。 */
export interface Evidence {
  id: string; tool_name: string; source: string; observation: string
  java_query_id: string; data_version: string | null; collected_at: string
  warnings: string[]; scope_version: number
}
/** 带支持和反对证据的调查假设，记录状态、理由和置信度。 */
export interface Hypothesis {
  id: string; statement: string; status: string; reason: string | null
  supporting_evidence_ids: string[]; contradicting_evidence_ids: string[]; scope_version: number
}
/** 操作员授权的优惠事实，文案和方案不得自行发明额外优惠。 */
export interface PromotionFact { type: string; discount?: number; description?: string }
/** 类型化活动配置，串联人群、渠道、排程、频控和授权优惠事实。 */
export interface CampaignDsl {
  campaignName: string; objective: string; channel: string
  audience: { logic: string; conditions: { field: string; operator: string; value: unknown }[] }
  schedule: { type: string; sendAt?: string; timezone?: string }
  frequencyCap: { maxTimes: number; windowHours: number }
  promotionFacts: PromotionFact[]
}
/** 保存待人工审核的 DSL、校验结果、预估快照及已确认活动关联。 */
export interface CampaignDraft {
  draftId: number; status: string; dsl: CampaignDsl
  errors: string[]; warnings: string[]; estimatedAudienceCount: number | null; dataVersion: string | null
}
/** 可从数据库恢复和向接口返回的完整调查快照。 */
export interface Investigation {
  id: string; goal: string; status: string; scope: string | null; scope_version: number
  messages: { role: 'USER' | 'ASSISTANT'; content: string }[]
  evidence: Evidence[]; hypotheses: Hypothesis[]; tool_trajectory: string[]
  final_diagnosis: Diagnosis | null
  proposals: {
    id: string; investigationId: string; scopeVersion: number
    status: 'GENERATED' | 'DRAFT_CREATED' | 'SUPERSEDED' | 'CANCELLED'; draftId: number | null
    proposal: {
      campaignName: string; objective: string; rationale: string; targetAudience: CampaignDsl['audience']
      channel: string; schedule: CampaignDsl['schedule']; frequencyCap: CampaignDsl['frequencyCap']
      promotionFacts: PromotionFact[]; supportingEvidenceIds: string[]
    }
  }[]
}
