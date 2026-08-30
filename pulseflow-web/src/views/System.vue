<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { getSystemStatus } from '@/api/system'
import type { SystemStatus } from '@/types/api'

const loading = ref(false)
const status = ref<SystemStatus>()
const services = [
  { key: 'backend', label: 'Backend', note: 'Spring Boot API' },
  { key: 'mysql', label: 'MySQL', note: '事实数据与查询' },
  { key: 'redis', label: 'Redis', note: '实时画像与频控' },
  { key: 'kafka', label: 'Kafka', note: '事件与触达消息' },
  { key: 'aiMode', label: 'AI Mode', note: 'Copilot provider' },
  { key: 'piiGuardrail', label: 'PII Guardrail', note: '输入安全边界' },
] as const

const tone = (value?: string) => ['UP', 'MOCK', 'REAL'].includes(value || '') ? 'success' : value === 'DEGRADED' ? 'warning' : value === 'DOWN' ? 'danger' : 'info'
const load = async () => { loading.value = true; try { status.value = await getSystemStatus() } catch (error) { ElMessage.error(error instanceof Error ? error.message : '系统状态加载失败') } finally { loading.value = false } }
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><h1>System</h1><p>轻量运维观察：只展示组件状态和 AI 模式，不暴露任何敏感配置。</p></div><button class="secondary-button" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新状态' }}</button></div>
  <section class="panel"><div class="panel-header"><h2 class="panel-title">运行状态</h2><span class="panel-subtitle">safe status only</span></div><div v-if="status" class="panel-body system-grid"><div v-for="service in services" :key="service.key" class="system-card"><div><div class="system-card-label">{{ service.label }}</div><div class="system-card-value">{{ service.note }}</div></div><span class="status-tag" :class="tone(status[service.key])">{{ status[service.key] }}</span></div></div><div v-else class="empty-state">正在读取系统状态…</div></section>
  <section class="panel" style="margin-top:16px"><div class="panel-header"><h2 class="panel-title">链路说明</h2></div><div class="panel-body"><div class="review-list"><div class="review-item"><strong>事件 → 画像</strong><p>事件写入 Kafka，用户行为落盘 MySQL，实时画像由 Redis 提供；Redis 不可用时 User 360 明确标记 MySQL fallback。</p></div><div class="review-item"><strong>AI → Guardrail → Campaign</strong><p>AI 只生成 DSL、洞察和文案；字段校验、人群预估、优惠事实和正式 Campaign 创建仍由后端权威。</p></div><div class="review-item"><strong>Campaign → 触达 → 归因</strong><p>规则引擎决定触达，Delivery 和 Click 形成 Last-Touch 归因，AI Review 以真实效果摘要为输入。</p></div></div></div></section>
</template>
