export interface Diagnosis {
  status: 'DIAGNOSED' | 'INSUFFICIENT_EVIDENCE'
  summary: string
  confidence: 'low' | 'medium' | 'high'
  findings: { claim: string; evidence_ids: string[] }[]
  evidence_ids: string[]
  unresolved_questions: string[]
  recommended_next_action: string | null
}
export interface Evidence {
  id: string; tool_name: string; source: string; observation: string
  java_query_id: string; data_version: string | null; collected_at: string
  warnings: string[]; scope_version: number
}
export interface Hypothesis {
  id: string; statement: string; status: string; reason: string | null
  supporting_evidence_ids: string[]; contradicting_evidence_ids: string[]; scope_version: number
}
export interface PromotionFact { type: string; discount?: number; description?: string }
export interface CampaignDsl {
  campaignName: string; objective: string; channel: string
  audience: { logic: string; conditions: { field: string; operator: string; value: unknown }[] }
  schedule: { type: string; sendAt?: string; timezone?: string }
  frequencyCap: { maxTimes: number; windowHours: number }
  promotionFacts: PromotionFact[]
}
export interface CampaignDraft {
  draftId: number; status: string; dsl: CampaignDsl
  errors: string[]; warnings: string[]; estimatedAudienceCount: number | null; dataVersion: string | null
}
export interface Investigation {
  id: string; goal: string; status: string; scope: string | null; scope_version: number
  messages: { role: 'USER' | 'ASSISTANT'; content: string }[]
  evidence: Evidence[]; hypotheses: Hypothesis[]; tool_trajectory: string[]
  final_diagnosis: Diagnosis | null
  proposals: { id: string; scope_version: number; proposal: { rationale: string; supporting_evidence_ids: string[] }; draft: { draftId: number } }[]
}
