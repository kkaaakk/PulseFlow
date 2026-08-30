<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Calendar, DataAnalysis, Message, Promotion, User } from '@element-plus/icons-vue'

import { getCampaign, getCampaignDeliveryTrend } from '@/api/campaigns'
import StatusTag from '@/components/StatusTag.vue'
import TrendChart from '@/components/TrendChart.vue'
import type { CampaignDetail as CampaignDetailData, TrendPoint } from '@/types/api'
import { formatDateTime, formatNumber, formatPercent } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const detail = ref<CampaignDetailData>()
const deliveryTrend = ref<TrendPoint[]>([])
const campaignId = computed(() => Number(route.params.id))
const campaign = computed(() => detail.value?.campaign)
const review = computed(() => detail.value?.aiReview)
const reviewData = computed(() => review.value?.review ?? {})
const reviewSummary = computed(() => typeof reviewData.value.summary === 'string' ? reviewData.value.summary : '复盘结果尚未生成。')
const reviewHighlights = computed(() => Array.isArray(reviewData.value.highlights) ? reviewData.value.highlights as Array<{ title?: string; description?: string }> : [])

const load = async () => {
  loading.value = true
  try { const [campaignResult, trendResult] = await Promise.all([getCampaign(campaignId.value), getCampaignDeliveryTrend(campaignId.value)]); detail.value = campaignResult; deliveryTrend.value = trendResult } catch (error) { ElMessage.error(error instanceof Error ? error.message : 'Campaign 详情加载失败') } finally { loading.value = false }
}

const configValue = (key: string) => {
  const config = detail.value?.rules.find((rule) => rule.name === key)?.config
  return typeof config === 'object' && config !== null ? JSON.stringify(config) : '—'
}

onMounted(load)
</script>

<template>
  <div v-if="loading" class="panel empty-state">正在加载 Campaign 详情…</div>
  <template v-else-if="campaign && detail">
    <div class="page-heading">
      <div>
        <button class="text-button" @click="router.push('/campaigns')"><el-icon><ArrowLeft /></el-icon>返回 Campaigns</button>
        <div class="detail-header" style="margin-top: 16px">
          <div><h1 class="detail-title">{{ campaign.name }}</h1><div class="detail-meta"><span>创建于 {{ formatDateTime(campaign.createdAt) }}</span><span>创建人 <strong>{{ campaign.createdBy ?? 'Legacy' }}</strong></span><span>渠道 <strong>{{ campaign.channel }}</strong></span></div></div>
          <StatusTag :status="campaign.status" />
        </div>
      </div>
      <div class="page-actions"><button class="secondary-button" @click="router.push(`/deliveries?campaignId=${campaign.id}`)">查看触达</button><button class="text-button" @click="router.push('/copilot')">创建相似 Campaign</button></div>
    </div>

    <div class="detail-kpis">
      <div class="detail-kpi"><div class="detail-kpi-label">目标人群</div><div class="detail-kpi-value">{{ formatNumber(detail.audience.estimatedCount) }}</div></div>
      <div class="detail-kpi"><div class="detail-kpi-label">已发送</div><div class="detail-kpi-value">{{ formatNumber(detail.deliverySummary.sent) }}</div></div>
      <div class="detail-kpi"><div class="detail-kpi-label">已送达</div><div class="detail-kpi-value">{{ formatNumber(detail.deliverySummary.delivered) }}</div></div>
      <div class="detail-kpi"><div class="detail-kpi-label">已点击</div><div class="detail-kpi-value">{{ formatNumber(detail.deliverySummary.clicked) }}</div></div>
      <div class="detail-kpi"><div class="detail-kpi-label">已转化</div><div class="detail-kpi-value">{{ formatNumber(detail.deliverySummary.converted) }}</div></div>
    </div>

    <div class="detail-grid" style="margin-top: 18px">
      <div class="detail-main">
        <section class="panel">
          <div class="panel-header"><h2 class="panel-title">触达表现</h2><span class="chart-legend">发送趋势</span></div>
          <div class="chart-wrap"><TrendChart :points="deliveryTrend" /></div>
          <div style="display:flex;gap:28px;padding:0 18px 18px;color:var(--pf-muted);font-size:12px"><span>送达率 <strong style="color:var(--pf-ink)">{{ formatPercent(detail.deliverySummary.deliveryRate) }}</strong></span><span>点击率 <strong style="color:var(--pf-ink)">{{ formatPercent(detail.deliverySummary.clickRate) }}</strong></span><span>转化率 <strong style="color:var(--pf-ink)">{{ formatPercent(detail.deliverySummary.conversionRate) }}</strong></span></div>
        </section>
        <section class="panel">
          <div class="panel-header"><h2 class="panel-title">人群规则</h2><span class="panel-subtitle">{{ detail.audience.calculationMode || 'AUTHORITATIVE' }}</span></div>
          <div class="panel-body"><div v-if="detail.rules.length" class="rule-list"><div v-for="rule in detail.rules" :key="rule.id" class="rule-row"><div><div class="rule-code">{{ rule.name }}</div><div class="rule-type">{{ rule.type }} · {{ configValue(rule.name) }}</div></div><StatusTag :status="rule.enabled ? 'VALIDATED' : 'PAUSED'" :label="rule.enabled ? '启用' : '停用'" /></div></div><div v-else class="empty-state">暂无规则</div></div>
        </section>
        <section class="panel">
          <div class="panel-header"><h2 class="panel-title">归因</h2><span class="panel-subtitle">Last-Touch</span></div>
          <div class="panel-body" style="display:grid;grid-template-columns:repeat(3,1fr);gap:18px"><div><div class="field-label">归因转化</div><div class="profile-metric-value">{{ formatNumber(detail.attributionSummary.attributedConversions) }}</div></div><div><div class="field-label">模型</div><div class="profile-metric-value" style="font-size:15px">{{ detail.attributionSummary.model }}</div></div><div><div class="field-label">窗口</div><div class="profile-metric-value" style="font-size:15px">{{ detail.attributionSummary.windowHours }} 小时</div></div></div>
        </section>
      </div>

      <aside class="detail-side">
        <section class="panel">
          <div class="panel-header"><h2 class="panel-title">Campaign 设置</h2></div>
          <div class="panel-body">
            <div class="setting-row"><span class="field-label"><el-icon><Calendar /></el-icon> 触发</span><strong>{{ campaign.triggerType }}</strong></div>
            <div class="setting-row"><span class="field-label"><el-icon><Message /></el-icon> 渠道</span><strong>{{ campaign.channel }}</strong></div>
            <div class="setting-row"><span class="field-label"><el-icon><User /></el-icon> 日频控</span><strong>{{ campaign.userDailyLimit ?? '—' }} 次 / 用户</strong></div>
            <div class="setting-row"><span class="field-label"><el-icon><Promotion /></el-icon> 周频控</span><strong>{{ campaign.campaignWeeklyLimit ?? '—' }} 次 / 活动</strong></div>
            <div class="setting-row"><span class="field-label"><el-icon><DataAnalysis /></el-icon> 版本</span><strong>{{ detail.audience.dataVersion || '—' }}</strong></div>
          </div>
        </section>
        <section class="panel">
          <div class="panel-header"><h2 class="panel-title">AI Campaign Review</h2><StatusTag v-if="review" :status="review.status" /><span v-else class="panel-subtitle">暂无复盘</span></div>
          <div class="panel-body">
            <template v-if="review">
              <div class="review-block"><div class="review-summary">{{ reviewSummary }}</div><div v-if="reviewHighlights.length" class="review-list"><div v-for="item in reviewHighlights" :key="item.title" class="review-item"><strong>{{ item.title }}</strong><p>{{ item.description }}</p></div></div><div v-if="review.errorMessage" class="status-tag danger">{{ review.errorMessage }}</div><div class="panel-subtitle">{{ review.model }} · 更新于 {{ formatDateTime(review.updatedAt) }}</div></div>
            </template>
            <div v-else class="empty-state" style="min-height:140px">Campaign 结束并有足够数据后，AI Review 会在这里出现。</div>
          </div>
        </section>
      </aside>
    </div>
  </template>
</template>
