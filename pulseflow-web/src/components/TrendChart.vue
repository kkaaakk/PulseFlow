<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

import type { TrendPoint } from '@/types/api'

const props = withDefaults(defineProps<{
  points: TrendPoint[]
  color?: string
  area?: boolean
  percent?: boolean
  smooth?: boolean
}>(), {
  color: '#5966d8',
  area: false,
  percent: false,
  smooth: true,
})

const chartElement = ref<HTMLElement>()
let chart: echarts.ECharts | undefined

/**
 * 在容器就绪后按需初始化 ECharts，根据 points 和展示属性覆盖趋势图配置；复用已有实例。
 */
const render = () => {
  if (!chartElement.value) return
  if (!chart) chart = echarts.init(chartElement.value)
  chart.setOption({
    animationDuration: 500,
    grid: { top: 20, right: 14, bottom: 24, left: 42 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#17213e',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      /**
       * 按 percent 属性将提示框数值格式化为一位小数百分比或中文分组数字。
       */
      valueFormatter: (value: unknown) => props.percent ? `${(Number(value) * 100).toFixed(1)}%` : new Intl.NumberFormat('zh-CN').format(Number(value)),
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: props.points.map((point) => point.label),
      axisLine: { lineStyle: { color: '#e2e7f0' } },
      axisTick: { show: false },
      axisLabel: { color: '#8b95a8', fontSize: 11, interval: props.points.length > 12 ? 3 : 0 },
    },
    yAxis: {
      type: 'value',
      min: props.percent ? 0 : undefined,
      max: props.percent ? undefined : undefined,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#edf0f5', type: 'dashed' } },
      axisLabel: { color: '#8b95a8', fontSize: 11, /** 格式化纵轴刻度：比例显示整数百分比，普通数值使用 K 或 M 简写。 */ formatter: (value: number) => props.percent ? `${(value * 100).toFixed(0)}%` : compactNumber(value) },
    },
    series: [{
      type: 'line',
      data: props.points.map((point) => point.value),
      smooth: props.smooth,
      symbol: props.points.length > 12 ? 'none' : 'circle',
      symbolSize: 6,
      lineStyle: { width: 2, color: props.color },
      itemStyle: { color: props.color, borderColor: '#fff', borderWidth: 2 },
      areaStyle: props.area ? { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: `${props.color}28` }, { offset: 1, color: `${props.color}02` }]) } : undefined,
    }],
  }, true)
}

/**
 * 按绝对值将百万与千级数值格式化为 M、K 简写，较小数值原样转为文本。
 */
const compactNumber = (value: number) => {
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (Math.abs(value) >= 1_000) return `${Math.round(value / 1_000)}K`
  return String(value)
}

/**
 * 在图表实例存在时同步容器尺寸，供窗口 resize 事件复用。
 */
const resize = () => chart?.resize()

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', resize)
})
watch(() => [props.points, props.color, props.area, props.percent], render, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<template>
  <div ref="chartElement" class="chart-canvas" aria-label="趋势图" />
</template>
