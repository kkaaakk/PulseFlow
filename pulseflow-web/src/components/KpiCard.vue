<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  label: string
  value: string
  icon: Component
  foot?: string
  positive?: boolean
}>()
</script>

<template>
  <article class="kpi-card">
    <div class="kpi-label">
      <span>{{ label }}</span>
      <el-icon><component :is="icon" /></el-icon>
    </div>
    <div class="kpi-value">{{ value }}</div>
    <div v-if="foot" class="kpi-foot">
      <span v-if="positive" class="positive">↑ {{ foot }}</span>
      <span v-else>{{ foot }}</span>
      <span v-if="positive">较昨日</span>
    </div>
  </article>
</template>
