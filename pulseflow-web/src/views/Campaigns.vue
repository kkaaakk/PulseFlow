<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { listCampaigns } from '@/api/campaigns'
import StatusTag from '@/components/StatusTag.vue'
import type { CampaignListItem, PageResponse } from '@/types/api'
import { formatDateTime, formatNumber, formatPercent } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const status = ref('')
const page = ref(1)
const pageSize = ref(10)
const result = ref<PageResponse<CampaignListItem>>({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0 })

const load = async () => {
  loading.value = true
  try {
    result.value = await listCampaigns({ page: page.value, pageSize: pageSize.value, keyword: keyword.value || undefined, status: status.value || undefined })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Campaign 数据加载失败')
  } finally {
    loading.value = false
  }
}

const filter = () => { page.value = 1; load() }
const changePage = (value: number) => { page.value = value; load() }
const openCampaign = (id: number) => router.push(`/campaigns/${id}`)
const openCampaignRow = (row: CampaignListItem) => openCampaign(row.id)

onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div><h1>Campaigns</h1><p>围绕人群、规则和触达结果管理运营活动。</p></div>
    <button class="primary-button" @click="router.push('/copilot')">创建 Campaign</button>
  </div>

  <section class="panel table-panel">
    <div class="table-toolbar">
      <div class="filter-row">
        <el-input v-model="keyword" clearable placeholder="搜索 Campaign 名称" style="width: 230px" @keyup.enter="filter" @clear="filter" />
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 145px" @change="filter">
          <el-option label="运行中" value="ACTIVE" /><el-option label="草稿" value="DRAFT" /><el-option label="已暂停" value="PAUSED" /><el-option label="已结束" value="CLOSED" />
        </el-select>
        <button class="secondary-button" @click="filter">筛选</button>
      </div>
      <span class="panel-subtitle">共 {{ formatNumber(result.total) }} 个 Campaign</span>
    </div>
    <div v-loading="loading" class="table-scroll">
      <el-table v-if="result.items.length" :data="result.items" row-key="id" @row-click="openCampaignRow">
        <el-table-column prop="name" label="Campaign" min-width="235" />
        <el-table-column label="状态" width="105"><template #default="scope"><StatusTag :status="scope.row.status" /></template></el-table-column>
        <el-table-column label="触发" width="105"><template #default="scope">{{ scope.row.triggerType }}</template></el-table-column>
        <el-table-column label="渠道" width="100"><template #default="scope">{{ scope.row.channel }}</template></el-table-column>
        <el-table-column label="人群" width="110"><template #default="scope">{{ formatNumber(scope.row.audience) }}</template></el-table-column>
        <el-table-column label="已发送" width="110"><template #default="scope">{{ formatNumber(scope.row.sent) }}</template></el-table-column>
        <el-table-column label="点击率" width="105"><template #default="scope">{{ scope.row.sent ? formatPercent(scope.row.clicked / scope.row.sent) : '—' }}</template></el-table-column>
        <el-table-column label="转化" width="105"><template #default="scope">{{ formatNumber(scope.row.converted) }}</template></el-table-column>
        <el-table-column label="创建时间" min-width="150"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column label="" width="54"><template #default="scope"><button class="text-button" :aria-label="`打开 ${scope.row.name}`" @click.stop="openCampaign(scope.row.id)">→</button></template></el-table-column>
      </el-table>
      <div v-else class="empty-state"><span>暂无符合条件的 Campaign</span><button class="text-button" @click="router.push('/copilot')">从 Copilot 创建一个</button></div>
    </div>
    <div class="pagination-row"><el-pagination background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="result.total" @current-change="changePage" /></div>
  </section>
</template>
