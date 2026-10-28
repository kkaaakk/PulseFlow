import { http, TOKEN_KEY, unwrapApiResponse } from './http'
import { isDemoMode } from './demo'
import type { ApiEnvelope } from '@/types/api'
import type { CampaignDraft, Investigation, PromotionFact } from '@/types/investigation'

// Deliberately no demo business results: offline UI honestly reports missing evidence.
let demoInvestigation: Investigation | null = null
/** 为调查请求设置较长超时，并解包统一业务响应，保留类型化返回值。 */
const request = async <T>(method: string, url: string, data?: unknown): Promise<T> => {
  const response = await http.request<ApiEnvelope<T>>({ method, url, data, timeout: 120_000 })
  return unwrapApiResponse(response.data)
}
/** 创建调查；演示模式只返回证据不足状态和空证据，避免伪造业务结论。 */
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
/** 读取调查详情；演示模式核对 ID 后返回副本，防止调用方修改共享状态。 */
export const getInvestigation = async (id: string): Promise<Investigation> => {
  if (isDemoMode) {
    if (demoInvestigation?.id !== id) throw new Error('演示调查已失效，请新建调查。')
    return structuredClone(demoInvestigation)
  }
  return request('GET', `/investigations/${encodeURIComponent(id)}`)
}
/** 在同一调查中追问并可更新范围；演示模式保存消息但不生成业务证据。 */
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
/** 通过 Java 网关取消调查，操作员身份由登录会话决定。 */
export const cancelInvestigation = (id: string): Promise<Investigation> => request('POST', `/investigations/${encodeURIComponent(id)}/cancel`)
/** 提交问题和操作员授权优惠事实，基于当前调查证据生成方案。 */
export const proposeCampaign = (id: string, question: string, promotionFacts: PromotionFact[]): Promise<Investigation> => request('POST', `/investigations/${encodeURIComponent(id)}/proposal`, { question, promotionFacts })
/** 按方案 ID 请求 Java 创建或返回关联草稿，供人工审查。 */
export const createDraft = (proposalId: string): Promise<{ draftId: number }> => request('POST', `/campaign-proposals/${encodeURIComponent(proposalId)}/draft`)
/** 读取草稿 DSL、校验错误、警告和人群快照。 */
export const getDraft = (id: number): Promise<CampaignDraft> => request('GET', `/campaign-drafts/${id}`)
/** 重新执行草稿人群预估，返回最新人数与数据版本。 */
export const refreshDraft = (id: number): Promise<CampaignDraft> => request('POST', `/campaign-drafts/${id}/refresh-preview`)
/** 人工确认草稿，返回新建或已关联的 DRAFT 活动 ID。 */
export const confirmDraft = (id: number): Promise<{ campaignId: number }> => request('POST', `/campaign-drafts/${id}/confirm`)

/** 用携带登录令牌的 fetch 读取 SSE，仅对白名单业务事件触发刷新；限制缓存并在退出时取消 reader。 */
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
        if (/^event: (investigation_started|tool_started|tool_completed|evidence_added|hypothesis_changed|proposal_ready|diagnosis_ready|error)$/m.test(frame)) changed()
      }
    }
    changed()
  } finally { await reader.cancel().catch(() => undefined) }
}
