<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, Delete, MagicStick, Plus, Refresh, User } from '@element-plus/icons-vue'

import { confirmCampaign, generateContent, generateInsight, parseCampaign, refreshPreview, updateDraft } from '@/api/campaigns'
import StatusTag from '@/components/StatusTag.vue'
import type { AudienceCondition, CampaignDsl, ContentResponse, ContentVariant, DraftResponse, InsightResponse, ParseResponse } from '@/types/api'
import { fieldOptions, operatorOptions, cloneDsl, conditionLabel, ensureCondition } from '@/utils/dsl'
import { formatDateTime, formatNumber } from '@/utils/format'

const router = useRouter()
const prompt = ref('针对最近 7 天活跃但 30 天没有购买的用户，明天下午 3 点推送一张 20 元优惠券，每个用户每天最多触达 1 次。')
const parsed = ref<ParseResponse>()
const draft = ref<DraftResponse>()
const dsl = ref<CampaignDsl>()
const insight = ref<InsightResponse>()
const content = ref<ContentResponse>()
const selectedVariant = ref('A')
const loading = reactive({ parse: false, save: false, preview: false, insight: false, content: false, confirm: false })

const hasDraft = computed(() => Boolean(draft.value && dsl.value))
const conditions = computed(() => dsl.value?.audience?.conditions ?? [])
const estimate = computed(() => draft.value?.estimatedAudience)
const contentVariants = computed<ContentVariant[]>(() => {
  const value = content.value?.content
  if (Array.isArray(value)) return value
  return value?.variants ?? []
})
const selectedContent = computed(() => contentVariants.value.find((item, index) => selectedKey(item, index) === selectedVariant.value) ?? contentVariants.value[0])
const insightSummary = computed(() => {
  const value = insight.value?.insight?.summary
  return typeof value === 'string' ? value : '生成洞察后，这里会展示基于 Java 聚合指标的解释。'
})
const insightFindings = computed(() => Array.isArray(insight.value?.insight?.findings) ? insight.value?.insight?.findings as Array<{ title?: string; description?: string }> : [])
const insightSuggestions = computed(() => Array.isArray(insight.value?.insight?.strategySuggestions) ? insight.value?.insight?.strategySuggestions as Array<{ suggestion?: string; reason?: string }> : [])

const updateConditionField = (condition: AudienceCondition, field: string) => {
  const option = fieldOptions.find((item) => item.value === field)
  condition.field = field
  condition.valueType = option?.valueType ?? 'INTEGER'
  condition.value = option?.valueType === 'BOOLEAN' ? true : 0
}

const addCondition = () => {
  if (!dsl.value) return
  dsl.value.audience.conditions.push(ensureCondition({ field: 'activeDays7d', operator: 'GTE', valueType: 'INTEGER', value: 0 }))
}

const removeCondition = (index: number) => {
  if (!dsl.value) return
  dsl.value.audience.conditions.splice(index, 1)
}

const scheduleValue = computed({
  get: () => dsl.value?.schedule?.sendAt ?? '',
  set: (value: string) => { if (dsl.value) dsl.value.schedule.sendAt = value },
})

const saveRules = async (showMessage = true) => {
  if (!draft.value || !dsl.value) return
  loading.save = true
  try {
    const result = await updateDraft(draft.value.draftId, dsl.value)
    draft.value = result
    dsl.value = cloneDsl(result.dsl)
    if (showMessage) ElMessage.success('规则已保存，后端 Validator 已重新校验')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '规则保存失败')
  } finally { loading.save = false }
}

const loadInsight = async () => {
  if (!draft.value) return
  loading.insight = true
  try { insight.value = await generateInsight(draft.value.draftId) } catch (error) { ElMessage.warning(error instanceof Error ? error.message : '人群洞察暂不可用') } finally { loading.insight = false }
}

const loadContent = async () => {
  if (!draft.value) return
  loading.content = true
  try { content.value = await generateContent(draft.value.draftId); if (contentVariants.value[0]) selectedVariant.value = contentVariants.value[0].variant || 'A' } catch (error) { ElMessage.warning(error instanceof Error ? error.message : 'AI 文案暂不可用') } finally { loading.content = false }
}

const parse = async () => {
  if (!prompt.value.trim()) { ElMessage.warning('先描述你想创建的 Campaign'); return }
  loading.parse = true
  try {
    const result = await parseCampaign(prompt.value)
    parsed.value = result
    draft.value = { draftId: result.draftId, status: result.status, dsl: result.dsl, warnings: result.warnings, estimatedAudience: result.estimatedAudience }
    dsl.value = cloneDsl(result.dsl)
    insight.value = undefined
    content.value = undefined
    await Promise.allSettled([loadInsight(), loadContent()])
    ElMessage.success('Campaign 草稿已生成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI Parse 失败')
  } finally { loading.parse = false }
}

const refresh = async () => {
  if (!draft.value) return
  loading.preview = true
  try { draft.value = await refreshPreview(draft.value.draftId); dsl.value = cloneDsl(draft.value.dsl); ElMessage.success('人群预估已刷新') } catch (error) { ElMessage.error(error instanceof Error ? error.message : '人群预估刷新失败') } finally { loading.preview = false }
}

const confirm = async () => {
  if (!draft.value || !dsl.value) return
  await saveRules(false)
  if (draft.value.status !== 'VALIDATED') { ElMessage.warning('请先修正未通过校验的规则'); return }
  loading.confirm = true
  try {
    const result = await confirmCampaign(draft.value.draftId)
    ElMessage.success('Campaign 已创建，正在打开详情')
    await router.push(`/campaigns/${result.campaignId}`)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : 'Campaign 创建失败') } finally { loading.confirm = false }
}

const selectedKey = (item: ContentVariant, index: number) => item.variant || String.fromCharCode(65 + index)
</script>

<template>
  <div class="page-heading"><div><h1>AI Campaign Copilot</h1><p>把自然语言运营目标转成可校验、可观察、可确认的 Campaign 草稿。</p></div><StatusTag v-if="draft" :status="draft.status" /></div>

  <div class="copilot-layout">
    <section class="panel copilot-panel copilot-input">
      <div class="copilot-section"><h2 class="step-title"><span class="step-number">1</span>用一句话描述你的 Campaign</h2><p class="panel-subtitle" style="margin:12px 0 0;line-height:1.6">AI 只负责理解意图，规则、优惠事实和最终创建仍由后端 Guardrail 决定。</p><el-input v-model="prompt" type="textarea" maxlength="300" show-word-limit aria-label="Campaign 自然语言描述" /><button class="primary-button" :disabled="loading.parse" @click="parse"><el-icon><MagicStick /></el-icon>{{ loading.parse ? '生成中…' : '生成 Campaign' }}</button></div>
      <div v-if="parsed" class="copilot-section" style="margin-top:auto"><div class="detail-meta" style="margin:0"><span>Draft <strong>#{{ parsed.draftId }}</strong></span><span>Request <strong>{{ parsed.requestId.slice(-8) }}</strong></span></div><div v-if="parsed.missingFields.length" class="status-tag warning" style="margin-top:12px">缺少：{{ parsed.missingFields.join('、') }}</div><div v-if="parsed.warnings.length" class="panel-subtitle" style="margin-top:12px">{{ parsed.warnings.join('；') }}</div></div>
    </section>

    <section class="panel copilot-panel">
      <div class="copilot-section"><div class="copilot-section-header"><h2 class="step-title"><span class="step-number">2</span>规则与人群</h2><span v-if="draft?.status === 'VALIDATED'" class="validation-state"><el-icon><Check /></el-icon>已通过校验</span></div>
        <template v-if="hasDraft && dsl">
          <div class="copilot-section-header" style="margin-bottom:10px"><span class="field-label">人群规则</span><div style="display:flex;gap:7px;align-items:center"><StatusTag :status="draft?.status" /><span class="status-tag info">{{ dsl.audience.logic }}</span></div></div>
          <div class="condition-list">
            <template v-for="(condition, index) in conditions" :key="`${condition.field}-${index}`"><div v-if="index" class="and-divider">{{ dsl.audience.logic }}</div><div class="condition-row"><el-select :model-value="condition.field" aria-label="人群字段" @change="updateConditionField(condition, $event)"><el-option v-for="item in fieldOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-select v-model="condition.operator" aria-label="规则运算符"><el-option v-for="item in operatorOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-input v-if="condition.valueType !== 'BOOLEAN'" v-model="condition.value" aria-label="规则值" /><el-select v-else v-model="condition.value" aria-label="布尔规则值"><el-option label="是" :value="true" /><el-option label="否" :value="false" /></el-select><el-button text circle aria-label="删除规则" @click="removeCondition(index)"><el-icon><Delete /></el-icon></el-button></div></template>
          </div>
          <div style="display:flex;gap:10px;margin-top:12px"><button class="secondary-button add-condition" @click="addCondition"><el-icon><Plus /></el-icon>添加条件</button><button class="text-button" style="margin-top:12px" :disabled="loading.save" @click="saveRules()">{{ loading.save ? '保存中…' : '保存规则' }}</button></div>
        </template>
        <div v-else class="empty-state" style="min-height:190px"><el-icon><MagicStick /></el-icon><span>生成草稿后，在这里编辑 DSL 条件</span></div>
      </div>
      <div class="copilot-section">
        <div class="setting-row"><span class="field-label">触达时间</span><el-date-picker v-if="dsl" v-model="scheduleValue" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" placeholder="选择发送时间" style="width:100%" /><span v-else class="panel-subtitle">—</span></div>
        <div class="setting-row"><span class="field-label">触达渠道</span><el-select v-if="dsl" v-model="dsl.channel" style="width:100%"><el-option label="站内信" value="IN_APP" /><el-option label="Push" value="PUSH" /><el-option label="Email" value="EMAIL" /></el-select><span v-else class="panel-subtitle">—</span></div>
        <div class="setting-row"><span class="field-label">触达频次</span><div v-if="dsl" style="display:flex;gap:8px;align-items:center"><el-input v-model.number="dsl.frequencyCap.maxTimes" type="number" style="width:90px" /><span class="field-label">次 /</span><el-input v-model.number="dsl.frequencyCap.windowHours" type="number" style="width:90px" /><span class="field-label">小时</span></div><span v-else class="panel-subtitle">—</span></div>
        <div class="copilot-section-header" style="margin-top:18px;margin-bottom:0"><span v-if="draft" class="validation-state"><el-icon><Check /></el-icon>后端 Validator 作为最终裁决</span><button v-if="draft" class="text-button" :disabled="loading.preview" @click="refresh"><el-icon><Refresh /></el-icon>{{ loading.preview ? '刷新中…' : '刷新人群预估' }}</button></div>
      </div>
    </section>

    <section class="panel copilot-panel">
      <div class="copilot-section"><div class="copilot-section-header"><h2 class="step-title"><span class="step-number">3</span>预计人群</h2><span class="status-tag info">Java 计算</span></div><div class="audience-metric"><span class="audience-icon"><el-icon><User /></el-icon></span><div><div class="audience-value">{{ estimate ? formatNumber(estimate.count) : '—' }}</div><div class="audience-caption">用户 · {{ estimate?.calculationMode || '等待预估' }}</div></div></div><div v-if="estimate" class="detail-meta" style="margin:0"><span>数据版本 <strong>{{ estimate.dataVersion || '—' }}</strong></span><span v-if="estimate.warnings.length">{{ estimate.warnings.join('；') }}</span></div></div>
      <div class="copilot-section"><div class="copilot-section-header"><h2 class="panel-title">AI 人群洞察</h2><button v-if="draft" class="text-button" :disabled="loading.insight" @click="loadInsight">{{ loading.insight ? '生成中…' : insight ? '重新生成' : '生成洞察' }}</button></div><div class="insight-box"><p>{{ insightSummary }}</p><ul v-if="insightFindings.length"><li v-for="item in insightFindings" :key="item.title"><strong>{{ item.title }}</strong> · {{ item.description }}</li></ul><div v-if="insightSuggestions.length" style="margin-top:10px;color:var(--pf-primary);font-size:12px"><strong>策略建议：</strong>{{ insightSuggestions.map((item) => item.suggestion).join('；') }}</div></div><div v-if="insight" class="detail-meta" style="margin-top:12px"><span>基线：{{ insight.dataQuality.baselineType }}</span><span v-if="insight.dataQuality.proxyMetrics.length">代理指标 {{ insight.dataQuality.proxyMetrics.length }}</span><span v-if="insight.dataQuality.unavailableMetrics.length">不可用 {{ insight.dataQuality.unavailableMetrics.length }}</span></div></div>
      <div class="copilot-section"><div class="copilot-section-header"><div><h2 class="panel-title">AI 文案</h2><span class="panel-subtitle">优惠事实由服务端 promotionFacts 约束</span></div><button v-if="draft" class="text-button" :disabled="loading.content" @click="loadContent">{{ loading.content ? '生成中…' : content ? '重新生成' : '生成文案' }}</button></div><div v-if="contentVariants.length" class="content-list"><label v-for="(item, index) in contentVariants" :key="selectedKey(item, index)" class="content-option" :class="{ selected: selectedVariant === selectedKey(item, index) }"><input v-model="selectedVariant" type="radio" name="content-variant" :value="selectedKey(item, index)" /><div class="content-option-copy"><div class="content-option-title">{{ selectedKey(item, index) }} · {{ item.title }}</div><div class="content-option-body">{{ item.body }}</div><div class="content-option-footer"><span>{{ item.strategy || '服务端 promotion facts 约束' }}</span><span>{{ item.body.length }} / 100</span></div></div></label></div><div v-else class="empty-state" style="min-height:120px"><span>生成草稿后，这里会出现 3 个可选版本</span></div></div>
    </section>
  </div>

  <section v-if="hasDraft && dsl" class="panel confirm-panel"><div class="copilot-section-header" style="padding:18px 18px 0;margin:0"><h2 class="step-title"><span class="step-number">4</span>确认 Campaign</h2><StatusTag :status="draft?.status" /></div><div class="confirm-grid"><div class="confirm-cell"><div class="confirm-label"><el-icon><User /></el-icon>人群</div><div class="confirm-value">{{ conditions.map(conditionLabel).join(` ${dsl.audience.logic} `) || '—' }}<br /><span class="status-tag info">{{ estimate?.calculationMode || 'SNAPSHOT' }}</span></div></div><div class="confirm-cell"><div class="confirm-label">触达时间</div><div class="confirm-value">{{ formatDateTime(dsl.schedule.sendAt) }}</div></div><div class="confirm-cell"><div class="confirm-label">触达渠道</div><div class="confirm-value">{{ dsl.channel }}</div></div><div class="confirm-cell"><div class="confirm-label">触达频次</div><div class="confirm-value">{{ dsl.frequencyCap.maxTimes }} 次 / {{ dsl.frequencyCap.windowHours }} 小时</div></div><div v-if="selectedContent" class="confirm-cell"><div class="confirm-label">已选文案 · {{ selectedVariant }}</div><div class="confirm-value"><strong>{{ selectedContent.title }}</strong><br /><span class="panel-subtitle">{{ selectedContent.body }}</span></div></div><div class="confirm-cell"><button class="primary-button" :disabled="loading.confirm || draft?.status !== 'VALIDATED'" @click="confirm">{{ loading.confirm ? '创建中…' : '确认创建 Campaign' }}</button></div></div></section>
</template>
