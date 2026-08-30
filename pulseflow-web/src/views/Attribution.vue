<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { listAttributions } from '@/api/attribution'
import type { AttributionView, PageResponse } from '@/types/api'
import { formatDateTime, formatNumber } from '@/utils/format'

const loading = ref(false)
const campaignId = ref('')
const userId = ref('')
const page = ref(1)
const pageSize = ref(20)
const result = ref<PageResponse<AttributionView>>({ items: [], page: 1, pageSize: 20, total: 0, totalPages: 0 })
const load = async () => { loading.value = true; try { result.value = await listAttributions(page.value, pageSize.value, campaignId.value ? Number(campaignId.value) : undefined, userId.value || undefined) } catch (error) { ElMessage.error(error instanceof Error ? error.message : '归因数据加载失败') } finally { loading.value = false } }
const filter = () => { page.value = 1; load() }
const changePage = (value: number) => { page.value = value; load() }
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><h1>Attribution</h1><p>把点击、转化和 Campaign 连接起来，观察 Last-Touch 归因结果。</p></div><span class="status-tag info">CLICK_LAST_TOUCH · 24h</span></div>
  <section class="panel table-panel"><div class="table-toolbar"><div class="filter-row"><el-input v-model="campaignId" clearable placeholder="Campaign ID" style="width:140px" @keyup.enter="filter" @clear="filter" /><el-input v-model="userId" clearable placeholder="User ID" style="width:125px" @keyup.enter="filter" @clear="filter" /><button class="secondary-button" @click="filter">筛选</button></div><span class="panel-subtitle">共 {{ formatNumber(result.total) }} 条归因</span></div><div v-loading="loading" class="table-scroll"><el-table v-if="result.items.length" :data="result.items" row-key="id"><el-table-column label="User" width="110"><template #default="scope">#{{ scope.row.userId }}</template></el-table-column><el-table-column prop="campaignName" label="Campaign" min-width="220" /><el-table-column label="Click" width="110"><template #default="scope">#{{ scope.row.clickEventId ?? '—' }}</template></el-table-column><el-table-column prop="targetEventId" label="Conversion Event" min-width="210" /><el-table-column prop="attributionModel" label="Model" width="170" /><el-table-column label="Window" width="100"><template #default="scope">{{ scope.row.attributionWindowHours }}h</template></el-table-column><el-table-column label="Attributed At" width="180"><template #default="scope">{{ formatDateTime(scope.row.creditedAt) }}</template></el-table-column></el-table><div v-else class="empty-state">暂无归因记录</div></div><div class="pagination-row"><el-pagination background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="result.total" @current-change="changePage" /></div></section>
</template>
