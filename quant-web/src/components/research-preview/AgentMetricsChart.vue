<script setup lang="ts">
import * as echarts from 'echarts'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AGENT_NAMES } from '../../agent-team/presentation'
import { buildAgentSlots, displayValue } from '../../research-preview/presentation'
import type { AgentRun } from '../../agent-team/types'

const props = defineProps<{
  runs: AgentRun[]
  synthetic: boolean
}>()

const container = ref<HTMLDivElement | null>(null)
const slots = computed(() => buildAgentSlots(props.runs))
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

function renderChart(): void {
  if (!container.value) return
  if (!chart) chart = echarts.init(container.value)
  const labels = slots.value.map((slot) => AGENT_NAMES[slot.agentCode])
  chart.setOption({
    animation: false,
    backgroundColor: 'transparent',
    grid: { top: 30, right: 24, bottom: 26, left: 78 },
    legend: {
      top: 0,
      right: 8,
      textStyle: { color: '#9eb0c7' },
      data: ['评分', '置信度'],
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const rows = Array.isArray(params) ? params : []
        const title = typeof rows[0] === 'object' && rows[0] != null && 'axisValue' in rows[0]
          ? String(rows[0].axisValue)
          : ''
        const details = rows.map((row) => {
          if (typeof row !== 'object' || row == null) return ''
          const record = row as { seriesName?: string; value?: unknown; marker?: string }
          const value = record.value === '-' || record.value == null ? '暂无' : String(record.value)
          return `${record.marker ?? ''}${record.seriesName ?? ''}：${value}`
        })
        return [title, ...details].filter(Boolean).join('<br>')
      },
    },
    xAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { color: '#7287a2' },
      splitLine: { lineStyle: { color: '#1f3249' } },
    },
    yAxis: {
      type: 'category',
      data: labels,
      axisLabel: { color: '#bdcada' },
      axisLine: { lineStyle: { color: '#2a3f59' } },
      axisTick: { show: false },
    },
    series: [
      {
        name: '评分',
        type: 'bar',
        barMaxWidth: 13,
        itemStyle: { color: '#3d9be9', borderRadius: [0, 4, 4, 0] },
        data: slots.value.map((slot) => slot.run?.score ?? '-'),
      },
      {
        name: '置信度',
        type: 'bar',
        barMaxWidth: 13,
        itemStyle: { color: '#62c89f', borderRadius: [0, 4, 4, 0] },
        data: slots.value.map((slot) => slot.run?.confidence ?? '-'),
      },
    ],
  }, true)
}

onMounted(async () => {
  await nextTick()
  renderChart()
  if (container.value) {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(container.value)
  }
})

watch(
  () => props.runs,
  async () => {
    await nextTick()
    renderChart()
  },
  { deep: true },
)

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="chart-panel">
    <div class="panel-heading">
      <div><p class="eyebrow">PERSISTED VALUES ONLY</p><h2>六智能体评分与置信度</h2></div>
      <span v-if="synthetic" class="watermark-label">TEST_DEMO_EXPLICIT</span>
    </div>
    <div class="chart-shell">
      <div ref="container" class="chart" />
      <div v-if="synthetic" class="watermark">TEST_DEMO_EXPLICIT</div>
    </div>
    <div class="status-grid">
      <div v-for="slot in slots" :key="slot.agentCode">
        <b>{{ AGENT_NAMES[slot.agentCode] }}</b>
        <span>{{ slot.run?.status ?? '暂无' }} / {{ slot.run?.gateStatus ?? '暂无' }}</span>
        <small>评分 {{ displayValue(slot.run?.score) }} · 置信度 {{ displayValue(slot.run?.confidence) }}</small>
      </div>
    </div>
    <p class="boundary">图表仅呈现已有持久化值；缺失值保持“暂无”，前端不补0、不重算综合分。</p>
  </section>
</template>

<style scoped>
.chart-panel { padding: 20px; border: 1px solid #223a57; border-radius: 12px; background: #0e1a2b; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.panel-heading h2 { margin: 0; font-size: 19px; }.eyebrow { margin: 0 0 5px; color: #5eaaff; font-size: 10px; font-weight: 800; letter-spacing: .15em; }
.watermark-label { color: #ffc869; font: 11px ui-monospace, monospace; }.chart-shell { position: relative; min-height: 360px; }.chart { width: 100%; height: 360px; }
.watermark { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; color: rgba(255, 194, 92, .11); font: 800 32px ui-monospace, monospace; transform: rotate(-12deg); }
.status-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 7px; }.status-grid div { min-width: 0; padding: 8px; border: 1px solid #1f334b; border-radius: 7px; background: #091422; }
.status-grid b, .status-grid span, .status-grid small { display: block; overflow-wrap: anywhere; }.status-grid b { font-size: 10px; }.status-grid span { margin-top: 4px; color: #80a2c4; font-size: 9px; }.status-grid small { margin-top: 4px; color: #7d8fa5; font-size: 9px; }
.boundary { margin: 12px 0 0; color: #778aa3; font-size: 11px; }
@media (max-width: 1400px) { .status-grid { grid-template-columns: repeat(3, 1fr); } }
</style>
