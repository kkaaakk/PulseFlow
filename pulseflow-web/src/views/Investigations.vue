<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelInvestigation, confirmDraft, followUpInvestigation, getDraft, getInvestigation, proposeCampaign, refreshDraft, startInvestigation, watchInvestigation } from '@/api/investigations'
import { isDemoMode } from '@/api/demo'
import type { CampaignDraft, Investigation, PromotionFact } from '@/types/investigation'

const route = useRoute()
const router = useRouter()
const investigation = ref<Investigation | null>(null)
const question = ref('')
const scope = ref('')
const busy = ref(false)
const error = ref('')
const streamNotice = ref('')
const draft = ref<CampaignDraft | null>(null)
const proposalDialog = ref(false)
const proposalQuestion = ref('根据当前证据，为调查目标生成一个改进活动草稿。')
const offerDescription = ref('')
const offerAmount = ref<number | undefined>()
const campaignId = ref<number | null>(null)
let stream: InstanceType<typeof window.AbortController> | null = null
let refreshTimer: number | null = null
let generation = 0
const running = computed(() => investigation.value?.status === 'RUNNING')
const diagnosis = computed(() => investigation.value?.final_diagnosis)
const canPropose = computed(() => !isDemoMode && investigation.value?.status === 'COMPLETED' && !busy.value && diagnosis.value?.status === 'DIAGNOSED' && investigation.value?.evidence.some(e => e.scope_version === investigation.value?.scope_version))
const statusLabels: Record<string, string> = { RUNNING: '调查中', COMPLETED: '调查完成', INSUFFICIENT_EVIDENCE: '证据不足', BUDGET_EXHAUSTED: '预算已用尽', FAILED: '调查中断', CANCELLED: '已取消', OPEN: '待验证', SUPPORTED: '证据支持', WEAKENED: '证据削弱', REJECTED: '已排除' }
const evidenceLabel = (id: string) => `E${(investigation.value?.evidence.findIndex(e => e.id === id) ?? -1) + 1}`
const stopStream = () => { stream?.abort(); stream = null; if (refreshTimer) window.clearTimeout(refreshTimer); refreshTimer = null }
const reportError = (cause: unknown) => {
  const status = (cause as { response?: { status?: number } })?.response?.status
  error.value = status === 403 ? '你无权访问这项调查。' : status === 422 ? '输入未通过安全检查，请使用聚合业务问题。' : status === 429 ? '调查服务繁忙，请稍后重试。' : status === 409 ? '当前调查状态不允许此操作，请刷新后重试。' : '服务暂时不可用，请刷新或稍后重试。'
}
async function refresh() {
  const id = investigation.value?.id || String(route.params.id || '')
  if (!id) return
  const version = generation
  try {
    const item = await getInvestigation(id)
    if (version !== generation) return
    investigation.value = item
    error.value = ''
    if (item.status !== 'RUNNING') stopStream()
  } catch (cause) { if (version === generation) reportError(cause) }
}
function connect() {
  stopStream()
  streamNotice.value = ''
  if (!running.value || !investigation.value || isDemoMode) return
  stream = new window.AbortController()
  const current = stream
  const version = generation
  const changed = () => {
    if (version !== generation || current.signal.aborted || refreshTimer) return
    refreshTimer = window.setTimeout(() => { refreshTimer = null; void refresh() }, 200)
  }
  void watchInvestigation(investigation.value.id, current.signal, changed).catch(() => {
    if (!current.signal.aborted) { streamNotice.value = '状态连接已断开，可刷新继续查看。'; void refresh() }
  }).then(() => { if (!current.signal.aborted && running.value) streamNotice.value = '状态连接已结束，可刷新继续查看。' })
}
async function load(id: string) {
  generation += 1
  stopStream()
  investigation.value = null
  draft.value = null
  campaignId.value = null
  error.value = ''
  if (!id) return
  busy.value = true
  const version = generation
  try { const item = await getInvestigation(id); if (version === generation) { investigation.value = item; scope.value = item.scope || ''; connect() } }
  catch (cause) { if (version === generation) reportError(cause) }
  finally { if (version === generation) busy.value = false }
}
watch(() => String(route.params.id || ''), id => { void load(id) }, { immediate: true })
onBeforeUnmount(() => { generation += 1; stopStream() })
async function submit() {
  if (!question.value.trim() || busy.value || running.value) return
  busy.value = true
  error.value = ''
  try {
    const existing = investigation.value
    const item = existing ? await followUpInvestigation(existing.id, question.value.trim(), scope.value.trim() || undefined) : await startInvestigation(question.value.trim())
    question.value = ''
    investigation.value = item
    if (!existing) await router.replace(`/investigations/${item.id}`)
    else connect()
  } catch (cause) { reportError(cause) }
  finally { busy.value = false }
}
async function cancel() {
  if (!investigation.value) return
  busy.value = true
  try { investigation.value = await cancelInvestigation(investigation.value.id); stopStream() }
  catch (cause) { reportError(cause) }
  finally { busy.value = false }
}
async function improve() {
  question.value = '基于当前调查证据，提出可验证的改进方案，并说明风险和待验证项。'
  await submit()
}
async function propose() {
  if (!canPropose.value || !investigation.value || !proposalQuestion.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    const facts: PromotionFact[] = offerDescription.value.trim() ? [{ type: 'COUPON', description: offerDescription.value.trim(), ...(typeof offerAmount.value === 'number' && Number.isFinite(offerAmount.value) ? { discount: offerAmount.value } : {}) }] : []
    const result = await proposeCampaign(investigation.value.id, proposalQuestion.value.trim(), facts)
    draft.value = await getDraft(result.draftId)
    proposalDialog.value = false
    campaignId.value = null
    await refresh()
  } catch (cause) { reportError(cause) }
  finally { busy.value = false }
}
async function review(id: number) {
  busy.value = true
  try { draft.value = await getDraft(id); campaignId.value = null }
  catch (cause) { reportError(cause) }
  finally { busy.value = false }
}
async function rePreview() {
  if (!draft.value) return
  busy.value = true
  try { draft.value = await refreshDraft(draft.value.draftId) }
  catch (cause) { reportError(cause) }
  finally { busy.value = false }
}
async function confirm() {
  if (!draft.value || draft.value.errors.length || campaignId.value) return
  try { await ElMessageBox.confirm('确认以当前目标人群、渠道、计划和频控创建 Campaign？创建后状态为 DRAFT。', '确认活动草稿', { confirmButtonText: '确认创建', cancelButtonText: '返回检查' }) }
  catch { return }
  busy.value = true
  try { campaignId.value = (await confirmDraft(draft.value.draftId)).campaignId; draft.value.status = 'CONFIRMED'; ElMessage.success('Campaign 已创建，状态为 DRAFT') }
  catch (cause) { reportError(cause) }
  finally { busy.value = false }
}
</script>

<template>
  <section class="investigation-page">
    <div class="investigation-heading">
      <div><span class="eyebrow">GROWTH WORKSPACE</span><h1>增长调查</h1><p>围绕业务问题收集证据，验证假设，再决定下一步。</p></div>
      <el-button v-if="investigation" @click="router.push('/investigations')">新建调查</el-button>
    </div>
    <el-alert v-if="isDemoMode" title="演示模式 · 未连接业务数据，结果仅用于展示调查流程" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <div class="investigation-grid">
      <section class="workspace-card conversation-card" aria-label="调查对话">
        <div class="card-heading"><h2>Conversation</h2><el-tag v-if="investigation" :type="running ? 'primary' : 'info'">{{ statusLabels[investigation.status] || investigation.status }}</el-tag></div>
        <div class="conversation" aria-live="polite">
          <div v-if="!investigation" class="empty-conversation"><span class="empty-mark">↗</span><h3>从一个问题开始</h3><p>例如：最近 7 天的召回活动转化下降，主要发生在哪个渠道？</p><el-button text @click="question = '最近 7 天的召回活动转化下降，主要发生在哪个渠道？'">使用这个问题</el-button></div>
          <article v-for="(message, index) in investigation?.messages" :key="index" class="message" :class="message.role.toLowerCase()"><span class="message-role">{{ message.role === 'USER' ? '你' : 'Growth Investigator' }}</span><p>{{ message.content }}</p></article>
          <div v-if="running" class="running-note" role="status">正在收集证据与验证假设…</div>
        </div>
        <div v-if="investigation?.tool_trajectory.length" class="tool-activity"><h3>Tool activity</h3><span v-for="(tool, index) in investigation.tool_trajectory" :key="index" class="tool-chip">{{ tool }}</span></div>
        <div v-if="streamNotice" class="muted">{{ streamNotice }}</div>
        <form class="question-form" @submit.prevent="submit">
          <label for="question">{{ investigation ? '继续追问' : '调查问题' }}</label>
          <textarea id="question" v-model="question" :disabled="busy || running" maxlength="4000" rows="3" placeholder="描述聚合业务问题，请勿输入个人信息" />
          <label v-if="investigation" for="scope">调查范围（修改后重新收集证据）</label>
          <input v-if="investigation" id="scope" v-model="scope" :disabled="busy || running" maxlength="1000" placeholder="全部 / 渠道 / 时间窗口" />
          <div class="form-actions"><el-button v-if="investigation" :disabled="busy" @click="refresh().then(connect)">刷新</el-button><el-button v-if="running" :disabled="busy" @click="cancel">取消调查</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="running || !question.trim()" @click.prevent="submit">{{ investigation ? '发送追问' : '开始调查' }}</el-button></div>
        </form>
      </section>
      <aside class="workspace-card investigation-details" aria-label="调查工作区">
        <div class="card-heading"><h2>Investigation</h2><span class="muted">{{ investigation ? `范围 v${investigation.scope_version}` : '等待问题' }}</span></div>
        <h3>Goal</h3><p>{{ investigation?.goal || '调查目标将在这里显示。' }}</p>
        <h3>Scope</h3><p>{{ investigation?.scope || '整体业务范围' }}</p>
        <div class="section-heading"><h3>Evidence</h3><span class="count">{{ investigation?.evidence.length || 0 }}</span></div>
        <p v-if="!investigation?.evidence.length" class="muted">暂无业务证据。结论需要可追溯的聚合查询支持。</p>
        <article v-for="(item, index) in investigation?.evidence" :id="`evidence-${item.id}`" :key="item.id" class="evidence-card" :class="{ historical: item.scope_version !== investigation?.scope_version }">
          <div><strong>E{{ index + 1 }}</strong><span class="evidence-source">{{ item.source }}</span><el-tag v-if="item.scope_version !== investigation?.scope_version" size="small" type="info">历史背景</el-tag></div><p>{{ item.observation }}</p><small>版本 {{ item.data_version || '未知' }} · {{ new Date(item.collected_at).toLocaleString() }}</small><details><summary>来源与警告</summary><p>Query {{ item.java_query_id }}</p><p v-for="warning in item.warnings" :key="warning">{{ warning }}</p></details>
        </article>
        <div class="section-heading"><h3>Hypotheses</h3><span class="count">{{ investigation?.hypotheses.length || 0 }}</span></div>
        <p v-if="!investigation?.hypotheses.length" class="muted">尚未提出待验证假设。</p>
        <article v-for="item in investigation?.hypotheses" :key="item.id" class="hypothesis-card"><el-tag size="small" :type="item.status === 'SUPPORTED' ? 'success' : 'info'">{{ statusLabels[item.status] }}</el-tag><p>{{ item.statement }}</p><small>{{ item.reason }}</small><div><a v-for="id in [...item.supporting_evidence_ids, ...item.contradicting_evidence_ids]" :key="id" :href="`#evidence-${id}`" class="evidence-link">{{ evidenceLabel(id) }}</a></div></article>
        <h3>Open questions</h3><ul v-if="diagnosis?.unresolved_questions.length"><li v-for="item in diagnosis.unresolved_questions" :key="item">{{ item }}</li></ul><p v-else class="muted">调查中的未解问题将在这里显示。</p>
      </aside>
    </div>
    <section class="workspace-card diagnosis-card" aria-label="调查诊断">
      <div class="card-heading"><h2>Diagnosis</h2><span v-if="diagnosis" class="muted">置信度：{{ { low: '低', medium: '中', high: '高' }[diagnosis.confidence] }}</span></div>
      <p v-if="!diagnosis" class="muted">调查完成后，展示证据支持的结论与尚未解决的问题。</p>
      <template v-else><p v-if="running" class="muted">上一轮诊断，当前追问尚未完成。</p><p class="diagnosis-summary">{{ diagnosis.summary }}</p><article v-for="finding in diagnosis.findings" :key="finding.claim" class="finding"><p>{{ finding.claim }}</p><a v-for="id in finding.evidence_ids" :key="id" :href="`#evidence-${id}`" class="evidence-link">{{ evidenceLabel(id) }}</a></article><p v-if="diagnosis.recommended_next_action"><strong>建议下一步：</strong>{{ diagnosis.recommended_next_action }}</p></template>
      <div class="form-actions"><el-button :disabled="!diagnosis || running || busy" @click="improve">生成改进方案</el-button><el-button type="primary" :disabled="!canPropose" @click="proposalDialog = true">生成 Campaign Proposal</el-button></div>
      <p v-if="!canPropose" class="muted">形成有当前范围证据支持的诊断后，可生成活动草稿。</p>
      <div v-if="investigation?.proposals.length" class="proposal-list"><h3>已有方案与草稿</h3><article v-for="item in investigation.proposals" :key="item.id"><p>{{ item.proposal.rationale }}</p><span v-if="item.scope_version !== investigation.scope_version" class="muted">历史范围方案</span><el-button :disabled="busy" @click="review(item.draft.draftId)">检查 Draft #{{ item.draft.draftId }}</el-button></article></div>
    </section>
    <section v-if="draft" class="workspace-card draft-card" aria-label="草稿审核">
      <div class="card-heading"><h2>草稿审核 · #{{ draft.draftId }}</h2><el-tag>{{ draft.status }}</el-tag></div>
      <h3>{{ draft.dsl.campaignName }}</h3><div class="draft-facts"><p><strong>目标</strong>{{ draft.dsl.objective }}</p><p><strong>渠道</strong>{{ draft.dsl.channel }}</p><p><strong>计划</strong>{{ draft.dsl.schedule.type }} {{ draft.dsl.schedule.sendAt }} {{ draft.dsl.schedule.timezone }}</p><p><strong>频控</strong>每 {{ draft.dsl.frequencyCap.windowHours }} 小时最多 {{ draft.dsl.frequencyCap.maxTimes }} 次</p><p><strong>预估人群</strong>{{ draft.estimatedAudienceCount === null ? '暂不可用' : draft.estimatedAudienceCount.toLocaleString() }} · 版本 {{ draft.dataVersion || '未知' }}</p></div>
      <h3>目标人群 · {{ draft.dsl.audience.logic }}</h3><ul><li v-for="(condition, index) in draft.dsl.audience.conditions" :key="index">{{ condition.field }} {{ condition.operator }} {{ condition.value }}</li></ul><h3>优惠事实</h3><p v-if="!draft.dsl.promotionFacts?.length">无优惠承诺</p><p v-for="(fact, index) in draft.dsl.promotionFacts" :key="index">{{ fact.type }} · {{ fact.description }} {{ fact.discount }}</p><el-alert v-for="warning in draft.warnings" :key="warning" :title="warning" type="warning" :closable="false" /><el-alert v-for="item in draft.errors" :key="item" :title="item" type="error" :closable="false" />
      <p class="muted">检查后由你确认创建 Campaign。创建后的状态为 DRAFT，可在活动页面继续审核。</p><div class="form-actions"><el-button :disabled="busy || draft.status === 'CONFIRMED'" @click="rePreview">刷新人群预览</el-button><el-button type="primary" :disabled="busy || !!draft.errors.length || draft.status === 'CONFIRMED'" @click="confirm">确认创建 Campaign</el-button><router-link v-if="campaignId" :to="`/campaigns/${campaignId}`">查看 Campaign #{{ campaignId }} →</router-link></div>
    </section>
    <el-dialog v-model="proposalDialog" title="生成活动方案" width="min(560px, 94vw)"><form @submit.prevent="propose"><label for="proposal-question">方案要求</label><textarea id="proposal-question" v-model="proposalQuestion" rows="3" maxlength="4000" /><p class="muted">仅提供已批准的优惠事实；留空表示没有优惠承诺。</p><label for="offer-description">已批准的优惠说明（可选）</label><input id="offer-description" v-model="offerDescription" maxlength="1000" placeholder="如：活动期间可使用的已批准优惠券" /><label for="offer-amount">优惠金额（可选）</label><input id="offer-amount" v-model.number="offerAmount" type="number" min="0" step="0.01" /><div class="form-actions"><el-button :disabled="busy" @click="proposalDialog = false">返回</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!proposalQuestion.trim()" @click.prevent="propose">生成草稿</el-button></div></form></el-dialog>
  </section>
</template>

<style scoped>
.investigation-page { display: grid; gap: 20px; max-width: 1500px; margin: auto; }
.investigation-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.eyebrow { color: var(--pf-primary); font-size: 11px; font-weight: 700; letter-spacing: .12em; }
h1 { margin: 8px 0; font-size: 28px; } h2 { margin: 0; font-size: 17px; } h3 { font-size: 13px; margin: 20px 0 10px; }
p { line-height: 1.7; margin: 8px 0; overflow-wrap: anywhere; } small, .muted { color: var(--pf-muted); font-size: 12px; line-height: 1.65; }
.investigation-heading p { color: var(--pf-muted); }
.investigation-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: 20px; align-items: start; }
.workspace-card { background: white; border: 1px solid var(--pf-border); border-radius: 12px; padding: 24px; min-width: 0; }
.card-heading, .section-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.card-heading { padding-bottom: 16px; border-bottom: 1px solid var(--pf-border); }
.conversation { min-height: 240px; max-height: 510px; overflow-y: auto; padding: 16px 0; }.empty-conversation { text-align: center; padding: 35px 16px; }.empty-conversation p { color: var(--pf-muted); max-width: 390px; margin: auto; }.empty-mark { color: var(--pf-primary); font-size: 32px; }
.message { padding: 14px 16px; background: var(--pf-canvas); border-radius: 10px; margin-bottom: 14px; }.message.user { margin-left: 36px; background: var(--pf-primary-soft); }.message-role { color: var(--pf-primary); font-size: 11px; font-weight: 700; }.message p { white-space: pre-wrap; }.running-note { color: var(--pf-primary); padding: 12px; }
.tool-activity { border-top: 1px solid var(--pf-border); padding-bottom: 14px; }.tool-chip { display: inline-block; margin: 3px; padding: 5px 8px; font-size: 11px; background: var(--pf-canvas); border-radius: 5px; overflow-wrap: anywhere; max-width: 100%; }
label { display: block; margin: 12px 0 6px; font-size: 12px; font-weight: 600; } textarea, input { width: 100%; border: 1px solid var(--pf-border); border-radius: 8px; padding: 11px; color: var(--pf-ink); background: white; } textarea { resize: vertical; } :is(input, textarea):focus { outline: 2px solid var(--pf-primary-soft); border-color: var(--pf-primary); }
.form-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 16px; flex-wrap: wrap; }.form-actions .el-button { margin-left: 0; }
.count { color: var(--pf-muted); background: var(--pf-canvas); padding: 2px 8px; border-radius: 20px; }.evidence-card, .hypothesis-card { margin-top: 10px; padding: 13px; background: var(--pf-canvas); border: 1px solid var(--pf-border); border-radius: 8px; scroll-margin-top: 16px; }.historical { opacity: .7; }.evidence-source { margin: 0 10px; font-size: 11px; color: var(--pf-muted); } details { font-size: 11px; color: var(--pf-muted); margin-top: 8px; }.evidence-link { display: inline-block; margin: 4px 5px 4px 0; padding: 3px 8px; color: var(--pf-primary); background: var(--pf-primary-soft); border-radius: 4px; font-size: 11px; } ul { padding-left: 22px; line-height: 1.8; }.diagnosis-summary { font-size: 16px; }.finding { border-left: 2px solid var(--pf-primary); padding-left: 16px; margin: 18px 0; }.draft-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.draft-facts strong { display: block; color: var(--pf-muted); font-size: 11px; }.proposal-list article { border-top: 1px solid var(--pf-border); padding: 10px 0; }
@media (max-width: 1000px) { .investigation-grid { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .workspace-card { padding: 16px; } h1 { font-size: 24px; } .draft-facts { grid-template-columns: 1fr; } .message.user { margin-left: 12px; } .form-actions { justify-content: flex-start; } }
</style>
