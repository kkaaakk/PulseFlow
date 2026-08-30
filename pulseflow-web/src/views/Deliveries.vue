<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import { getDelivery, listDeliveries } from '@/api/deliveries'
import StatusTag from '@/components/StatusTag.vue'
import type { DeliveryDetail, DeliveryListItem, PageResponse } from '@/types/api'
import { formatDateTime, formatNumber } from '@/utils/format'

const loading = ref(false)
const route = useRoute()
const campaignId = ref('')
const userId = ref('')
const status = ref('')
const page = ref(1)
const pageSize = ref(20)
const result = ref<PageResponse<DeliveryListItem>>({ items: [], page: 1, pageSize: 20, total: 0, totalPages: 0 })
const drawerVisible = ref(false)
const detail = ref<DeliveryDetail>()

const load = async () => { loading.value = true; try { result.value = await listDeliveries(page.value, pageSize.value, campaignId.value ? Number(campaignId.value) : undefined, userId.value || undefined, status.value || undefined) } catch (error) { ElMessage.error(error instanceof Error ? error.message : '触达数据加载失败') } finally { loading.value = false } }
const filter = () => { page.value = 1; load() }
const openDelivery = async (taskId: number) => { try { detail.value = await getDelivery(taskId); drawerVisible.value = true } catch (error) { ElMessage.error(error instanceof Error ? error.message : '触达详情加载失败') } }
const openDeliveryRow = (row: DeliveryListItem) => openDelivery(row.taskId)
const changePage = (value: number) => { page.value = value; load() }
onMounted(() => { campaignId.value = typeof route.query.campaignId === 'string' ? route.query.campaignId : ''; load() })
</script>

<template>
  <div class="page-heading"><div><h1>Deliveries</h1><p>追踪 DeliveryTask 从频控、派发到发送记录的完整状态。</p></div><span class="status-tag info">运营观察 · 工程调试</span></div>
  <section class="panel table-panel"><div class="table-toolbar"><div class="filter-row"><el-input v-model="campaignId" clearable placeholder="Campaign ID" style="width:135px" @keyup.enter="filter" @clear="filter" /><el-input v-model="userId" clearable placeholder="User ID" style="width:125px" @keyup.enter="filter" @clear="filter" /><el-select v-model="status" clearable placeholder="触达状态" style="width:145px" @change="filter"><el-option label="已发送" value="SENT" /><el-option label="处理中" value="PROCESSING" /><el-option label="等待重试" value="WAIT_RETRY" /><el-option label="失败" value="FAILED" /></el-select><button class="secondary-button" @click="filter">筛选</button></div><span class="panel-subtitle">共 {{ formatNumber(result.total) }} 条任务</span></div><div v-loading="loading" class="table-scroll"><el-table v-if="result.items.length" :data="result.items" row-key="taskId" @row-click="openDeliveryRow"><el-table-column label="Task ID" width="120"><template #default="scope"><span class="rule-code">#{{ scope.row.taskId }}</span></template></el-table-column><el-table-column prop="campaignName" label="Campaign" min-width="220" /><el-table-column label="User" width="110"><template #default="scope">#{{ scope.row.userId }}</template></el-table-column><el-table-column prop="channel" label="渠道" width="110" /><el-table-column label="状态" width="125"><template #default="scope"><StatusTag :status="scope.row.status" /></template></el-table-column><el-table-column label="重试" width="80"><template #default="scope">{{ scope.row.retryCount }}</template></el-table-column><el-table-column label="创建时间" width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column><el-table-column label="发送时间" width="170"><template #default="scope">{{ formatDateTime(scope.row.sentAt) }}</template></el-table-column><el-table-column label="" width="90"><template #default="scope"><button class="text-button" @click.stop="openDelivery(scope.row.taskId)">查看详情</button></template></el-table-column></el-table><div v-else class="empty-state">暂无触达任务</div></div><div class="pagination-row"><el-pagination background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="result.total" @current-change="changePage" /></div></section>
  <el-drawer v-model="drawerVisible" title="Delivery 执行详情" size="520px"><template v-if="detail"><div class="detail-meta" style="margin:0 0 20px"><span>Task <strong>#{{ detail.task.taskId }}</strong></span><StatusTag :status="detail.task.status" /><span>{{ detail.task.channel }}</span></div><div class="panel" style="box-shadow:none"><div class="panel-body"><div class="setting-row"><span class="field-label">Campaign</span><strong>{{ detail.task.campaignName }}</strong></div><div class="setting-row"><span class="field-label">User</span><strong>#{{ detail.task.userId }}</strong></div><div class="setting-row"><span class="field-label">派发</span><strong>{{ detail.task.dispatchStatus || '—' }}</strong></div><div class="setting-row"><span class="field-label">发送记录</span><strong>{{ detail.record?.status || '暂无' }}</strong></div></div></div><h3 class="panel-title" style="margin:24px 0 12px">点击（{{ detail.clicks.length }}）</h3><div v-if="detail.clicks.length" class="review-list"><div v-for="click in detail.clicks" :key="click.id" class="review-item"><strong>{{ formatDateTime(click.clickTime) }}</strong><p>{{ click.clickSource || '—' }} · Click #{{ click.id }}</p></div></div><div v-else class="panel-subtitle">暂无点击</div><h3 class="panel-title" style="margin:24px 0 12px">归因（{{ detail.attributions.length }}）</h3><div v-if="detail.attributions.length" class="review-list"><div v-for="item in detail.attributions" :key="item.id" class="review-item"><strong>{{ item.attributionModel }}</strong><p>{{ item.targetEventId }} · {{ formatDateTime(item.creditedAt) }}</p></div></div><div v-else class="panel-subtitle">暂无归因</div></template></el-drawer>
</template>
