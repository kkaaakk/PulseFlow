<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ modelValue: boolean; title: string; value: unknown }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const formatted = computed(() => JSON.stringify(props.value ?? {}, null, 2))
</script>

<template>
  <el-drawer :model-value="modelValue" :title="title" size="420px" @update:model-value="emit('update:modelValue', $event)">
    <pre class="json-view">{{ formatted }}</pre>
  </el-drawer>
</template>
