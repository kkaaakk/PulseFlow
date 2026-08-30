<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { DataAnalysis, Lightning, Message, Promotion, User, TrendCharts } from '@element-plus/icons-vue'

import { getDashboardSummary, getDashboardTrends } from '@/api/dashboard'
import { listCampaigns } from '@/api/campaigns'
import KpiCard from '@/components/KpiCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import TrendChart from '@/components/TrendChart.vue'
import type { CampaignListItem, DashboardSummary, DashboardTrends } from '@/types/api'
import { formatNumber, formatPercent } from '@/utils/format'

const router = useRouter()
const loading = ref(true)
const summary = ref<DashboardSummary>()
const trends = ref<DashboardTrends>({ events: [], deliveries: [], conversions: [] })
const campaigns = ref<CampaignListItem[]>([])

const kpis = computed(() => {
  if (!summary.value) return []
  return [
    { label: '今日事件', value: formatNumber(summary.value.todayEvents), icon: Lightning, foot: '18.6%', positive: true },
    { label: '活跃用户', value: formatNumber(summary.value.activeUsers), icon: User, foot: '12.4%', positive: true },
    { label: '运行中 Campaign', value: formatNumber(summary.value.runningCampaigns), icon: Promotion, foot: '4', positive: true },
    { label: '今日触达', value: formatNumber(summary.value.todayDeliveries), icon: Message, foot: '15.2%', positive: true },
    { label: '点击', value: formatNumber(summary.value.todayClicks), icon: TrendCharts, foot: '11.3%', positive: true },
    { label: '转化', value: formatNumber(summary.value.todayConversions), icon: DataAnalysis, foot: '9.8%', positive: true },
  ]
})

const load = async () => {
  loading.value = true
  try {
    const [nextSummary, nextTrends, nextCampaigns] = await Promise.all([
      getDashboardSummary(),
      getDashboardTrends(),
      listCampaigns({ page: 1, pageSize: 5 }),
    ])
    summary.value = nextSummary
    trends.value = nextTrends
    campaigns.value = nextCampaigns.items
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Dashboard 数据加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>运营总览</h1>
      <p>从事件吞吐到 Campaign 结果，掌握今天正在发生的用户运营动作。</p>
    </div>
    <div class="page-actions">
      <button class="primary-button" @click="router.push('/copilot')">创建 Campaign</button>
      <button class="text-button" @click="router.push('/campaigns')">查看全部</button>
    </div>
  </div>

  <div v-if="loading" class="panel empty-state">正在加载运营数据…</div>
  <template v-else>
    <div class="kpi-grid">
      <KpiCard v-for="item in kpis" :key="item.label" v-bind="item" />
    </div>

    <div class="dashboard-main-grid">
      <section class="panel">
        <div class="panel-header">
          <h2 class="panel-title">事件吞吐</h2>
          <span class="chart-legend">事件数</span>
        </div>
        <div class="chart-wrap"><TrendChart :points="trends.events" :area="true" /></div>
      </section>
      <section class="panel">
        <div class="panel-header">
          <h2 class="panel-title">Campaign 表现</h2>
          <button class="text-button" @click="router.push('/campaigns')">查看全部</button>
        </div>
        <div class="table-scroll">
          <table class="metric-table">
            <thead><tr><th>Campaign</th><th>状态</th><th>触达</th><th>点击率</th><th>转化率</th></tr></thead>
            <tbody>
              <tr v-for="campaign in campaigns" :key="campaign.id" @click="router.push(`/campaigns/${campaign.id}`)">
                <td>{{ campaign.name }}</td>
                <td><StatusTag :status="campaign.status" /></td>
                <td>{{ formatNumber(campaign.sent) }}</td>
                <td>{{ campaign.sent ? formatPercent(campaign.clicked / campaign.sent) : '—' }}</td>
                <td>{{ campaign.sent ? formatPercent(campaign.converted / campaign.sent) : '—' }}</td>
              </tr>
              <tr v-if="!campaigns.length"><td colspan="5"><div class="empty-state">暂无 Campaign 数据</div></td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <div class="dashboard-lower-grid">
      <section class="panel">
        <div class="panel-header"><h2 class="panel-title">触达趋势</h2><span class="chart-legend">触达数</span></div>
        <div class="chart-wrap small"><TrendChart :points="trends.deliveries" /></div>
      </section>
      <section class="panel">
        <div class="panel-header"><h2 class="panel-title">转化趋势</h2><span class="chart-legend">转化率</span></div>
        <div class="chart-wrap small"><TrendChart :points="trends.conversions" :percent="true" /></div>
      </section>
      <section class="panel">
        <div class="panel-header"><h2 class="panel-title">需要关注</h2><button class="text-button" @click="router.push('/campaigns')">查看全部</button></div>
        <div class="attention-list">
          <div class="attention-item"><el-icon class="attention-icon"><Message /></el-icon><span class="attention-title">购物车召回 点击率下降</span><span class="status-tag warning">待处理</span><el-icon class="attention-arrow"><TrendCharts /></el-icon></div>
          <div class="attention-item"><el-icon class="attention-icon"><Promotion /></el-icon><span class="attention-title">沉睡用户唤醒 转化偏低</span><span class="status-tag warning">待处理</span><el-icon class="attention-arrow"><TrendCharts /></el-icon></div>
          <div class="attention-item"><el-icon class="attention-icon"><DataAnalysis /></el-icon><span class="attention-title">会员日促活 触达波动异常</span><span class="status-tag danger">告警</span><el-icon class="attention-arrow"><TrendCharts /></el-icon></div>
          <div class="attention-item"><el-icon class="attention-icon"><User /></el-icon><span class="attention-title">新客欢迎系列 频次过高</span><span class="status-tag warning">待处理</span><el-icon class="attention-arrow"><TrendCharts /></el-icon></div>
        </div>
      </section>
    </div>
  </template>
</template>
