import { http, TOKEN_KEY, unwrapApiResponse } from './http'
import { isDemoMode } from './demo'
import type { ApiEnvelope } from '@/types/api'
import type { CampaignDraft, Investigation, PromotionFact } from '@/types/investigation'

// Deliberately no demo business results: offline UI honestly reports missing evidence.
let demoInvestigation: Investigation | null = null
/**
 * 使用两分钟超时调用调查或草稿 API，并统一解包业务响应；错误继续交给页面处理。
 */
const request = async <T>(method: string, url: string, data?: unknown): Promise<T> => {
  const response = await http.request<ApiEnvelope<T>>({ method, url, data, timeout: 120_000 })
  return unwrapApiResponse(response.data)
}
/**
 * 按 question 创建调查；演示模式仅生成明确证据不足的本地快照，不伪造业务证据。
 */
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
/**
 * 按已编码的调查 ID 读取快照；演示模式仅接受当前缓存 ID 并返回深拷贝。
 */
export const getInvestigation = async (id: string): Promise<Investigation> => {
  if (isDemoMode) {
    if (demoInvestigation?.id !== id) throw new Error('演示调查已失效，请新建调查。')
    return structuredClone(demoInvestigation)
  }
  return request('GET', `/investigations/${encodeURIComponent(id)}`)
}
/**
 * 在原调查下提交追问和可选范围；演示模式追加可见消息，范围变化时递增版本且不增加证据。
 */
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
/**
 * 请求取消指定调查，返回服务端确认的最新调查状态。
 */
export const cancelInvestigation = (id: string): Promise<Investigation> => request('POST', `/investigations/${encodeURIComponent(id)}/cancel`)
/**
 * 传递调查 ID、问题及用户明确提供的促销事实，要求服务端生成有证据支持的提案。
 */
export const proposeCampaign = (id: string, question: string, promotionFacts: PromotionFact[]): Promise<Investigation> => request('POST', `/investigations/${encodeURIComponent(id)}/proposal`, { question, promotionFacts })
/**
 * 以已保存 proposalId 请求生成 Java 草稿，不在浏览器直接构造 Campaign 或替换促销事实。
 */
export const createDraft = (proposalId: string): Promise<{ draftId: number }> => request('POST', `/campaign-proposals/${encodeURIComponent(proposalId)}/draft`)
/**
 * 按草稿数值 ID 获取完整校验与人群预估详情，服务端负责所有权检查。
 */
export const getDraft = (id: number): Promise<CampaignDraft> => request('GET', `/campaign-drafts/${id}`)
/**
 * 请求重新计算并持久化草稿人群预估，返回新的草稿快照。
 */
export const refreshDraft = (id: number): Promise<CampaignDraft> => request('POST', `/campaign-drafts/${id}/refresh-preview`)
/**
 * 请求人工确认草稿，返回创建或幂等复用的 Campaign ID；活动初始状态由后端控制。
 */
export const confirmDraft = (id: number): Promise<{ campaignId: number }> => request('POST', `/campaign-drafts/${id}/confirm`)

/**
 * 携带令牌建立可取消的 SSE 连接，按完整帧识别白名单事件并调用 changed；缓冲超过 16384 字符时报错，退出始终取消 reader。
 * @param id 调查 UUID
 * @param signal 页面切换、卸载或主动停止时中止连接的信号
 * @param changed 只表示需要重读快照的回调，不接收或解析模型内容
 */
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
