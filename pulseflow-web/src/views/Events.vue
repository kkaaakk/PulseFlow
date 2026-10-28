<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { listEvents } from '@/api/events'
import JsonDrawer from '@/components/JsonDrawer.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { EventView, PageResponse } from '@/types/api'
import { formatDateTime, formatNumber } from '@/utils/format'

const loading = ref(false)
const eventType = ref('')
const userId = ref('')
const page = ref(1)
const pageSize = ref(20)
const result = ref<PageResponse<EventView>>({ items: [], page: 1, pageSize: 20, total: 0, totalPages: 0 })
const drawerVisible = ref(false)
const selectedEvent = ref<EventView>()

/** 按事件类型和用户筛选分页查询事件，维护加载状态并展示失败信息。 */
const load = async () => {
  loading.value = true
  try { result.value = await listEvents(page.value, pageSize.value, eventType.value || undefined, userId.value ? Number(userId.value) : undefined) } catch (error) { ElMessage.error(error instanceof Error ? error.message : '事件数据加载失败') } finally { loading.value = false }
}
/** 筛选条件变化时回到第一页并重新加载列表。 */
const filter = () => { page.value = 1; load() }
/** 保存选中的事件并打开 JSON 详情抽屉。 */
const openEvent = (event: EventView) => { selectedEvent.value = event; drawerVisible.value = true }
/** 保存新页码并重新查询，保持分页器与列表结果一致。 */
const changePage = (value: number) => { page.value = value; load() }
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><h1>Events</h1><p>观察原始行为事件与落盘时间，用于运营核对和工程调试。</p></div><span class="status-tag info">只读观察</span></div>
  <section class="panel table-panel">
    <div class="table-toolbar"><div class="filter-row"><el-input v-model="userId" clearable placeholder="User ID" style="width:130px" @keyup.enter="filter" @clear="filter" /><el-select v-model="eventType" clearable placeholder="事件类型" style="width:165px" @change="filter"><el-option label="CONTENT_VIEW" value="CONTENT_VIEW" /><el-option label="SEARCH" value="SEARCH" /><el-option label="ADD_CART" value="ADD_CART" /><el-option label="ORDER_PAID" value="ORDER_PAID" /><el-option label="LOGIN" value="LOGIN" /></el-select><button class="secondary-button" @click="filter">筛选</button></div><span class="panel-subtitle">共 {{ formatNumber(result.total) }} 条事件</span></div>
    <div v-loading="loading" class="table-scroll"><el-table v-if="result.items.length" :data="result.items" row-key="eventId" @row-click="openEvent"><el-table-column label="Event ID" min-width="220"><template #default="scope"><span class="rule-code">{{ scope.row.eventId }}</span></template></el-table-column><el-table-column label="User" width="120"><template #default="scope">#{{ scope.row.userId }}</template></el-table-column><el-table-column prop="eventType" label="Type" width="170" /><el-table-column label="Event Time" width="180"><template #default="scope">{{ formatDateTime(scope.row.eventTime) }}</template></el-table-column><el-table-column label="Received Time" width="180"><template #default="scope">{{ formatDateTime(scope.row.receivedAt) }}</template></el-table-column><el-table-column label="时钟偏差" width="100"><template #default="scope"><StatusTag v-if="scope.row.clockSkew" status="FAILED" label="已修正" /><span v-else class="panel-subtitle">—</span></template></el-table-column><el-table-column label="" width="100"><template #default="scope"><button class="text-button" @click.stop="openEvent(scope.row)">查看属性</button></template></el-table-column></el-table><div v-else class="empty-state">暂无事件数据</div></div>
    <div class="pagination-row"><el-pagination background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="result.total" @current-change="changePage" /></div>
  </section>
  <JsonDrawer v-model="drawerVisible" :title="selectedEvent ? `Event · ${selectedEvent.eventType}` : 'Event 属性'" :value="selectedEvent" />
</template>
