<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'

const summary = ref<any>({ positions: [] })
const openEvents = ref<any[]>([])
const resolvedEvents = ref<any[]>([])
const loading = ref(false)
const message = ref('')
const error = ref('')

function money(value: any) { return Number(value || 0).toFixed(2) }
function pct(value: any) { const n=Number(value||0)*100; return `${n>=0?'+':''}${n.toFixed(2)}%` }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [account, open, resolved] = await Promise.all([
      api.get('/portfolio'),
      api.get('/portfolio/risk-events'),
      api.get('/portfolio/risk-events', { params: { resolved: true } }),
    ])
    summary.value = account
    openEvents.value = open as unknown as any[]
    resolvedEvents.value = resolved as unknown as any[]
  } catch (e: any) {
    error.value = e.message || '风险中心加载失败'
  } finally { loading.value = false }
}

async function check() {
  loading.value = true; error.value=''; message.value=''
  try {
    const result: any = await api.post('/portfolio/refresh-risk')
    message.value = `已刷新${result.refreshedPositions || 0}个持仓并完成风险检查`
    await load()
  } catch (e: any) { error.value=e.message || '风险检查失败' }
  finally { loading.value=false }
}

async function resolve(row: any) {
  try {
    await api.post(`/portfolio/risk-events/${row.id}/resolve`)
    await load()
  } catch (e: any) { error.value=e.message || '处理失败' }
}

onMounted(load)
</script>

<template>
  <div class="title-row"><h1 class="page-title">持仓风险中心</h1><button class="btn" :disabled="loading" @click="check">{{ loading?'检查中...':'立即检查' }}</button></div>
  <div class="grid4">
    <div class="card metric"><label>未处理风险</label><strong :class="openEvents.length?'down':''">{{ openEvents.length }}</strong></div>
    <div class="card metric"><label>持仓数量</label><strong>{{ summary.positionCount || 0 }}</strong></div>
    <div class="card metric"><label>浮动盈亏</label><strong :class="Number(summary.unrealizedPnl)>=0?'up':'down'">{{ money(summary.unrealizedPnl) }}</strong></div>
    <div class="card metric"><label>账户收益</label><strong :class="Number(summary.totalReturn)>=0?'up':'down'">{{ pct(summary.totalReturn) }}</strong></div>
  </div>
  <p v-if="message" class="success">{{ message }}</p><p v-if="error" class="error">{{ error }}</p>
  <div class="risk-layout">
    <div class="card"><h3>未处理风险事件</h3><div v-for="row in openEvents" :key="row.id" class="event"><div><b :class="row.level==='HIGH'?'down':''">{{ row.symbol }} · {{ row.event_type }} · {{ row.level }}</b><p>{{ row.message }}</p><small>现价 {{ row.current_price || '--' }} / 触发线 {{ row.trigger_price || '--' }} / {{ row.event_time }}</small></div><button class="btn secondary" @click="resolve(row)">标记处理</button></div><div v-if="!openEvents.length" class="muted empty">当前没有未处理风险</div></div>
    <div class="card"><h3>当前持仓风险线</h3><table class="table"><thead><tr><th>股票</th><th>现价</th><th>成本</th><th>收益</th><th>止损</th><th>目标</th><th>移动线</th></tr></thead><tbody><tr v-for="row in summary.positions || []" :key="row.symbol"><td>{{ row.symbol }} {{ row.name }}</td><td>{{ row.last_price }}</td><td>{{ row.average_cost }}</td><td :class="Number(row.pnl_rate)>=0?'up':'down'">{{ pct(row.pnl_rate) }}</td><td class="down">{{ row.stop_loss || '--' }}</td><td class="up">{{ row.target_price || '--' }}</td><td>{{ row.trailing_stop_price || '--' }}</td></tr></tbody></table></div>
  </div>
  <div class="card history"><h3>已处理风险记录</h3><table class="table"><thead><tr><th>股票</th><th>类型</th><th>级别</th><th>说明</th><th>触发时间</th><th>处理时间</th></tr></thead><tbody><tr v-for="row in resolvedEvents" :key="row.id"><td>{{ row.symbol }}</td><td>{{ row.event_type }}</td><td>{{ row.level }}</td><td>{{ row.message }}</td><td>{{ row.event_time }}</td><td>{{ row.resolved_at }}</td></tr></tbody></table></div>
</template>

<style scoped>.title-row{display:flex;align-items:center;justify-content:space-between}.risk-layout{display:grid;grid-template-columns:1fr 1.5fr;gap:12px;margin-top:12px}.event{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid #1b2b40}.event b{font-size:12px}.event p{font-size:12px;margin:5px 0;color:#a8b5c7}.event small{color:#71819c}.empty{text-align:center;padding:28px}.history{margin-top:12px}@media(max-width:1400px){.risk-layout{grid-template-columns:1fr}}</style>
