<script setup lang="ts">
import { computed } from 'vue'

import { getStatusLabel, statusTone } from '@/utils/status'

const props = withDefaults(defineProps<{ status?: string; label?: string }>(), { status: '' })
const tone = computed(() => statusTone(props.status))
const text = computed(() => props.label ?? getStatusLabel(props.status))
</script>

<template>
  <span class="status-tag" :class="tone">{{ text }}</span>
</template>
