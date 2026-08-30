<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Search, ShoppingCart, View } from '@element-plus/icons-vue'

import { getUser } from '@/api/users'
import JsonDrawer from '@/components/JsonDrawer.vue'
import type { EventView, UserDetail as UserDetailData } from '@/types/api'
import { formatCurrency, formatDateTime, formatNumber } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const user = ref<UserDetailData>()
const drawerVisible = ref(false)
const selectedEvent = ref<EventView>()
const userId = computed(() => Number(route.params.id))
const profile = computed(() => user.value?.profile)
const initials = computed(() => (profile.value?.nickname || String(profile.value?.userId || 'U')).slice(0, 1))
const realtime = computed(() => user.value?.realtimeMetrics ?? {})
const windowMetrics = computed(() => user.value?.windowMetrics ?? {})

const load = async () => {
  loading.value = true
  try { user.value = await getUser(userId.value) } catch (error) { ElMessage.error(error instanceof Error ? error.message : 'User 360 加载失败') } finally { loading.value = false }
}

const openEvent = (event: EventView) => { selectedEvent.value = event; drawerVisible.value = true }
const eventTime = (event: EventView) => formatDateTime(event.effectiveEventTime || event.eventTime)
onMounted(load)
</script>

<template>
  <div v-if="loading" class="panel empty-state">正在加载 User 360…</div>
  <template v-else-if="user && profile">
    <div class="page-heading">
      <div><button class="text-button" @click="router.push('/users')"><el-icon><ArrowLeft /></el-icon>返回 Users</button><div class="profile-summary" style="margin-top:16px"><span class="profile-avatar">{{ initials }}</span><div><h1 class="detail-title">{{ profile.nickname || `用户 #${profile.userId}` }}</h1><div class="profile-id">User ID #{{ profile.userId }} · 最近活跃 {{ formatDateTime(realtime.lastActiveAt) }}</div></div></div></div>
    </div>

    <section class="panel">
      <div class="panel-header"><h2 class="panel-title">实时画像</h2><span class="panel-subtitle">来源：{{ user.realtimeSource }}<span v-if="!user.realtimeAvailable"> · Redis 不可用，已使用 MySQL fallback</span></span></div>
      <div class="panel-body profile-sections">
        <div class="profile-metric"><div class="profile-metric-label"><el-icon><View /></el-icon> 今日浏览</div><div class="profile-metric-value">{{ realtime.todayViews ?? '0' }}</div></div>
        <div class="profile-metric"><div class="profile-metric-label"><el-icon><Search /></el-icon> 今日搜索</div><div class="profile-metric-value">{{ realtime.todaySearches ?? '0' }}</div></div>
        <div class="profile-metric"><div class="profile-metric-label"><el-icon><ShoppingCart /></el-icon> 购物车</div><div class="profile-metric-value">{{ realtime.cartCount ?? '0' }}</div></div>
      </div>
    </section>

    <div class="dashboard-main-grid" style="margin-top:16px">
      <section class="panel"><div class="panel-header"><h2 class="panel-title">窗口指标</h2><span class="panel-subtitle">后端计算</span></div><div class="panel-body profile-sections" style="margin-top:0"><div class="profile-metric"><div class="profile-metric-label">活跃天数 7d</div><div class="profile-metric-value">{{ formatNumber(Number(windowMetrics.activeDays7d || 0), 0) }} 天</div></div><div class="profile-metric"><div class="profile-metric-label">消费金额 30d</div><div class="profile-metric-value">{{ formatCurrency(Number(windowMetrics.spend30d || 0)) }}</div></div><div class="profile-metric"><div class="profile-metric-label">搜索次数 1h</div><div class="profile-metric-value">{{ formatNumber(Number(windowMetrics.search1h || 0), 0) }}</div></div></div></section>
      <section class="panel"><div class="panel-header"><h2 class="panel-title">长期标签</h2><span class="panel-subtitle">最新有效标签</span></div><div class="panel-body"><div class="tag-list"><span v-for="tag in user.tags" :key="tag" class="tag">{{ tag }}</span><span v-if="!user.tags.length" class="panel-subtitle">暂无长期标签</span></div><p class="panel-subtitle" style="margin:18px 0 0;line-height:1.6">标签由画像策略异步计算，展示最近一次有效结果。</p></div></section>
    </div>

    <section class="panel" style="margin-top:16px"><div class="panel-header"><h2 class="panel-title">Event Timeline</h2><span class="panel-subtitle">最近 {{ user.recentEvents.items.length }} 条行为</span></div><div class="panel-body"><div v-if="user.recentEvents.items.length" class="timeline"><button v-for="event in user.recentEvents.items" :key="event.eventId" class="timeline-row" style="border:0;border-bottom:1px solid var(--pf-border);background:transparent;text-align:left" @click="openEvent(event)"><span class="timeline-time">{{ eventTime(event) }}</span><span class="timeline-dot" /><span><span class="timeline-type">{{ event.eventType }}</span><span class="timeline-sub">Event {{ event.eventId }}<span v-if="event.targetId"> · Target {{ event.targetId }}</span></span></span><span class="text-button">查看属性</span></button></div><div v-else class="empty-state">暂无最近行为</div></div></section>
    <JsonDrawer v-model="drawerVisible" :title="selectedEvent ? `Event · ${selectedEvent.eventType}` : 'Event 属性'" :value="selectedEvent" />
  </template>
</template>
