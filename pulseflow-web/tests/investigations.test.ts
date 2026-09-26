import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox, type MessageBoxData } from 'element-plus'
import Investigations from '@/views/Investigations.vue'
import * as api from '@/api/investigations'
import type { CampaignDraft, Investigation } from '@/types/investigation'

vi.mock('@/api/investigations', () => ({ getInvestigation: vi.fn(), startInvestigation: vi.fn(), followUpInvestigation: vi.fn(), cancelInvestigation: vi.fn(), watchInvestigation: vi.fn(), proposeCampaign: vi.fn(), getDraft: vi.fn(), refreshDraft: vi.fn(), confirmDraft: vi.fn() }))
vi.mock('@/api/demo', () => ({ isDemoMode: false }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'investigation-1' } }), useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }))
const sample: Investigation = {
  id: 'investigation-1', goal: '分析转化下降', status: 'COMPLETED', scope: '最近7天', scope_version: 1,
  messages: [{ role: 'USER', content: '分析转化下降' }, { role: 'ASSISTANT', content: '渠道差异需要验证。' }],
  evidence: [{ id: 'evidence-1', tool_name: 'query_metric', source: 'metrics', observation: '聚合转化率 2%', java_query_id: 'query-1', data_version: 'v1', collected_at: '2026-09-26T01:00:00Z', warnings: [], scope_version: 1 }],
  hypotheses: [{ id: 'hypothesis-1', statement: '渠道差异', status: 'SUPPORTED', reason: '聚合数据支持', supporting_evidence_ids: ['evidence-1'], contradicting_evidence_ids: [], scope_version: 1 }], tool_trajectory: ['query_metric'], proposals: [],
  final_diagnosis: { status: 'DIAGNOSED', summary: '当前范围存在渠道差异。', confidence: 'medium', findings: [{ claim: '观察到渠道差异', evidence_ids: ['evidence-1'] }], evidence_ids: ['evidence-1'], unresolved_questions: ['需要进一步拆分时间窗口。'], recommended_next_action: null },
}
const draft: CampaignDraft = { draftId: 77, status: 'VALIDATED', dsl: { campaignName: '召回测试', objective: 'RETENTION', channel: 'PUSH', audience: { logic: 'AND', conditions: [{ field: 'activeDays7d', operator: 'GTE', value: 5 }] }, schedule: { type: 'ONCE', sendAt: '2026-10-01T10:00:00+08:00', timezone: 'Asia/Shanghai' }, frequencyCap: { maxTimes: 1, windowHours: 24 }, promotionFacts: [] }, errors: [], warnings: ['请人工检查计划时间'], estimatedAudienceCount: 42, dataVersion: 'v1' }
const wrappers: ReturnType<typeof mount>[] = []
const open = () => { const wrapper = mount(Investigations, { global: { plugins: [ElementPlus], stubs: { teleport: true, RouterLink: { template: '<a><slot /></a>' } } } }); wrappers.push(wrapper); return wrapper }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.getInvestigation).mockResolvedValue(structuredClone(sample)); vi.mocked(api.proposeCampaign).mockResolvedValue({ draftId: 77 }); vi.mocked(api.getDraft).mockResolvedValue(structuredClone(draft)); vi.mocked(api.confirmDraft).mockResolvedValue({ campaignId: 99 }) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })
describe('Investigation workspace', () => {
  it('shows visible evidence, hypotheses and diagnosis, and preserves scope in follow-up', async () => {
    const wrapper = open(); await flushPromises()
    expect(wrapper.text()).toContain('聚合转化率 2%')
    expect(wrapper.text()).toContain('证据支持')
    expect(wrapper.find('a[href="#evidence-evidence-1"]').exists()).toBe(true)
    vi.mocked(api.followUpInvestigation).mockResolvedValue(structuredClone(sample))
    await wrapper.get('#question').setValue('进一步分析')
    await wrapper.get('.question-form').trigger('submit')
    await flushPromises()
    expect(api.followUpInvestigation).toHaveBeenCalledWith('investigation-1', '进一步分析', '最近7天')
  })
  it('blocks proposal generation for insufficient or historical evidence', async () => {
    vi.mocked(api.getInvestigation).mockResolvedValue({ ...structuredClone(sample), status: 'INSUFFICIENT_EVIDENCE', evidence: [] })
    const wrapper = open(); await flushPromises()
    const button = wrapper.findAll('button').find(button => button.text() === '生成 Campaign Proposal')!
    expect(button.attributes('disabled')).toBeDefined()
    expect(api.proposeCampaign).not.toHaveBeenCalled()
  })
  it('creates only a draft until the user accepts the confirmation dialog', async () => {
    const wrapper = open(); await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '生成 Campaign Proposal')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('form').find(form => form.find('#proposal-question').exists())!.trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('草稿审核 · #77')
    expect(wrapper.text()).toContain('请人工检查计划时间')
    expect(api.confirmDraft).not.toHaveBeenCalled()
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    await wrapper.findAll('button').find(button => button.text() === '确认创建 Campaign')!.trigger('click'); await flushPromises()
    expect(api.confirmDraft).not.toHaveBeenCalled()
    confirmation.mockResolvedValue('confirm' as MessageBoxData)
    await wrapper.findAll('button').find(button => button.text() === '确认创建 Campaign')!.trigger('click'); await flushPromises()
    expect(api.confirmDraft).toHaveBeenCalledExactlyOnceWith(77)
    expect(wrapper.text()).toContain('查看 Campaign #99')
  })
})
