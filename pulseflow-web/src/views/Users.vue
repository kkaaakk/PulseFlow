<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { listUsers } from '@/api/users'
import type { PageResponse, UserListItem } from '@/types/api'
import { formatCurrency, formatDateTime, formatNumber } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const result = ref<PageResponse<UserListItem>>({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0 })

const load = async () => {
  loading.value = true
  try { result.value = await listUsers(page.value, pageSize.value, keyword.value || undefined) } catch (error) { ElMessage.error(error instanceof Error ? error.message : '用户数据加载失败') } finally { loading.value = false }
}
const filter = () => { page.value = 1; load() }
const openUser = (userId: number) => router.push(`/users/${userId}`)
const openUserRow = (row: UserListItem) => openUser(row.userId)
const changePage = (value: number) => { page.value = value; load() }
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div><h1>Users</h1><p>从长期标签、窗口指标和最近行为理解每一个用户。</p></div>
  </div>
  <section class="panel table-panel">
    <div class="table-toolbar"><div class="filter-row"><el-input v-model="keyword" clearable placeholder="搜索 User ID / 昵称" style="width: 230px" @keyup.enter="filter" @clear="filter" /><button class="secondary-button" @click="filter">筛选</button></div><span class="panel-subtitle">共 {{ formatNumber(result.total) }} 个用户</span></div>
    <div v-loading="loading" class="table-scroll">
      <el-table v-if="result.items.length" :data="result.items" row-key="userId" @row-click="openUserRow">
        <el-table-column label="User ID" width="140"><template #default="scope"><span class="rule-code">#{{ scope.row.userId }}</span></template></el-table-column>
        <el-table-column prop="nickname" label="用户" min-width="210" />
        <el-table-column label="最近活跃" width="170"><template #default="scope">{{ formatDateTime(scope.row.lastActiveAt) }}</template></el-table-column>
        <el-table-column label="活跃天数 7d" width="140"><template #default="scope">{{ formatNumber(scope.row.activeDays7d, 0) }} 天</template></el-table-column>
        <el-table-column label="消费 30d" width="140"><template #default="scope">{{ formatCurrency(scope.row.spend30d) }}</template></el-table-column>
        <el-table-column label="标签" min-width="240"><template #default="scope"><div class="tag-list"><span v-for="tag in scope.row.tags" :key="tag" class="tag">{{ tag }}</span><span v-if="!scope.row.tags.length" class="panel-subtitle">暂无标签</span></div></template></el-table-column>
        <el-table-column label="" width="54"><template #default="scope"><button class="text-button" :aria-label="`打开用户 ${scope.row.userId}`" @click.stop="openUser(scope.row.userId)">→</button></template></el-table-column>
      </el-table>
      <div v-else class="empty-state">暂无用户数据</div>
    </div>
    <div class="pagination-row"><el-pagination background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="result.total" @current-change="changePage" /></div>
  </section>
</template>
