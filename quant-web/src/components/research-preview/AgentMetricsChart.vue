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
    grid: { top: 30, right: 22, bottom: 24, left: 92 },
    legend: {
      top: 0,
      right: 8,
      textStyle: { color: '#aebdd0', fontSize: 12 },
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
      axisLabel: { color: '#94a7be', fontSize: 12 },
      splitLine: { lineStyle: { color: '#1f3249' } },
    },
    yAxis: {
      type: 'category',
      data: labels,
      axisLabel: { color: '#c8d4e1', fontSize: 12 },
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
      <span>缺失值保持“暂无”</span>
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
.chart-panel { padding: 18px; border: 1px solid #263f5b; border-radius: 12px; background: #0e1b2c; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.panel-heading h2 { margin: 0; font-size: 19px; }.eyebrow { margin: 0 0 5px; color: #73b7f2; font-size: 11px; font-weight: 800; letter-spacing: .13em; }
.panel-heading > span { color: #91a5bc; font-size: 12px; }.chart-shell { position: relative; min-height: 285px; }.chart { width: 100%; height: 285px; }
.watermark { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; color: rgba(255, 194, 92, .1); font: 800 28px ui-monospace, monospace; transform: rotate(-10deg); }
.status-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 7px; }.status-grid div { min-width: 0; padding: 9px; border: 1px solid #1f334b; border-radius: 7px; background: #091422; }
.status-grid b, .status-grid span, .status-grid small { display: block; overflow-wrap: anywhere; }.status-grid b { font-size: 12px; }.status-grid span { margin-top: 4px; color: #9bb0c8; font-size: 12px; }.status-grid small { margin-top: 4px; color: #91a4ba; font-size: 11px; }
.boundary { margin: 12px 0 0; color: #93a5ba; font-size: 12px; }
@media (max-width: 1400px) { .status-grid { grid-template-columns: repeat(3, 1fr); } }
</style>
