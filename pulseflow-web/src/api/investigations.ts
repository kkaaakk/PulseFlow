import { http, TOKEN_KEY, unwrapApiResponse } from './http'
import { isDemoMode } from './demo'
import type { ApiEnvelope } from '@/types/api'
import type { CampaignDraft, Investigation, PromotionFact } from '@/types/investigation'

// Deliberately no demo business results: offline UI honestly reports missing evidence.
let demoInvestigation: Investigation | null = null
const request = async <T>(method: string, url: string, data?: unknown): Promise<T> => {
  const response = await http.request<ApiEnvelope<T>>({ method, url, data, timeout: 120_000 })
  return unwrapApiResponse(response.data)
}
export const startInvestigation = async (question: string): Promise<Investigation> => {
  if (!isDemoMode) return request('POST', '/investigations', { question })
  demoInvestigation = {
    id: crypto.randomUUID(), goal: question, status: 'INSUFFICIENT_EVIDENCE', scope: null, scope_version: 0,
    messages: [{ role: 'USER', content: question }, { role: 'ASSISTANT', content: '演示模式未查询业务数据，暂无可支持结论的证据。' }],
    evidence: [], hypotheses: [], tool_trajectory: [], proposals: [],
    final_diagnosis: { status: 'INSUFFICIENT_EVIDENCE', summary: '演示模式未查询业务数据，暂无可支持结论的证据。', confidence: 'low', findings: [], evidence_ids: [], unresolved_questions: ['需要连接 Agent 服务和 Java 聚合指标。'], recommended_next_action: null },
  }
  return structuredClone(demoInvestigation)
}
export const getInvestigation = async (id: string): Promise<Investigation> => {
  if (isDemoMode) {
    if (demoInvestigation?.id !== id) throw new Error('演示调查已失效，请新建调查。')
    return structuredClone(demoInvestigation)
  }
  return request('GET', `/investigations/${encodeURIComponent(id)}`)
}
export const followUpInvestigation = async (id: string, question: string, scope?: string): Promise<Investigation> => {
  if (isDemoMode) {
    const item = await getInvestigation(id)
    item.messages.push({ role: 'USER', content: question }, { role: 'ASSISTANT', content: '暂无新增业务证据，请连接真实服务后继续调查。' })
    if (scope && scope !== item.scope) { item.scope = scope; item.scope_version += 1 }
    demoInvestigation = item
    return item
  }
  return request('POST', `/investigations/${encodeURIComponent(id)}/follow-up`, { question, scope })
}
export const cancelInvestigation = (id: string): Promise<Investigation> => request('POST', `/investigations/${encodeURIComponent(id)}/cancel`)
export const proposeCampaign = (id: string, question: string, promotionFacts: PromotionFact[]): Promise<{ draftId: number }> => request('POST', `/investigations/${encodeURIComponent(id)}/proposal`, { question, promotionFacts })
export const getDraft = (id: number): Promise<CampaignDraft> => request('GET', `/campaign-drafts/${id}`)
export const refreshDraft = (id: number): Promise<CampaignDraft> => request('POST', `/campaign-drafts/${id}/refresh-preview`)
export const confirmDraft = (id: number): Promise<{ campaignId: number }> => request('POST', `/campaign-drafts/${id}/confirm`)

export async function watchInvestigation(id: string, signal: AbortSignal, changed: () => void): Promise<void> {
  const base = http.defaults.baseURL?.replace(/\/$/, '') || '/api'
  const response = await fetch(`${base}/investigations/${encodeURIComponent(id)}/events`, {
    signal, headers: { token: sessionStorage.getItem(TOKEN_KEY) || '', Accept: 'text/event-stream' },
  })
  if (!response.ok || !response.body) throw new Error('调查状态连接不可用，请点击刷新。')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r/g, '')
      if (buffer.length > 16384) throw new Error('调查状态连接不可用，请点击刷新。')
      let end: number
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, end)
        buffer = buffer.slice(end + 2)
        if (/^event: (investigation_started|tool_started|tool_completed|evidence_added|hypothesis_changed|diagnosis_ready|error)$/m.test(frame)) changed()
      }
    }
    changed()
  } finally { await reader.cancel().catch(() => undefined) }
}
