<script setup lang="ts">
// 按统一中文标签与颜色映射展示业务状态。
import { computed } from 'vue'

import { getStatusLabel, statusTone } from '@/utils/status'

const props = withDefaults(defineProps<{ status?: string; label?: string }>(), { status: '' })
const tone = computed(() => statusTone(props.status))
const text = computed(() => props.label ?? getStatusLabel(props.status))
</script>

<template>
  <span class="status-tag" :class="tone">{{ text }}</span>
</template>
