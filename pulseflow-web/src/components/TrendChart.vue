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

/** 按最新数据初始化或更新 ECharts，设置系列、坐标轴和提示格式。 */
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
      /** 按百分比或中文数字格式展示提示框数值。 */
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
      axisLabel: { color: '#8b95a8', fontSize: 11, /** 按指标类型格式化纵轴刻度，较大数值使用紧凑表示。 */ formatter: (value: number) => props.percent ? `${(value * 100).toFixed(0)}%` : compactNumber(value) },
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

/** 把千和百万级数值转换为 K/M 刻度，减少坐标轴文字宽度。 */
const compactNumber = (value: number) => {
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (Math.abs(value) >= 1_000) return `${Math.round(value / 1_000)}K`
  return String(value)
}

/** 容器尺寸变化时调整已有图表，尚未初始化时安全跳过。 */
const resize = () => chart?.resize()

// 组件挂载后创建图表并监听容器或窗口尺寸变化。
onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', resize)
})
// 数据或指标配置变化时重绘图表，保持视图与输入一致。
watch(() => [props.points, props.color, props.area, props.percent], render, { deep: true })
// 卸载时注销尺寸监听并销毁 ECharts，避免资源泄露。
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<template>
  <div ref="chartElement" class="chart-canvas" aria-label="趋势图" />
</template>
