<script setup lang="ts">
// 以抽屉展示选中的结构化业务数据，方便查看事件及触达详情。
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
