<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api } from '../api'

const rows = ref<any[]>([])
const task = ref<any>({})
const loading = ref(false)
const starting = ref(false)
const error = ref('')
const form = reactive({ scanLimit: 200, batchSize: 12, resultLimit: 50 })
let timer: number | undefined

const running = computed(() => ['QUEUED', 'RUNNING'].includes(task.value?.status))
const progress = computed(() => {
  const total = Number(task.value?.total_symbols || 0)
  return total ? Math.round(Number(task.value?.processed_symbols || 0) * 100 / total) : 0
})

async function loadRows() {
  loading.value = true
  try {
    rows.value = await api.get('/scans/latest?limit=100') as unknown as any[]
    task.value = await api.get('/scans/latest-task')
    if (running.value) startPolling()
  } catch (e: any) {
    error.value = e.message || '候选结果加载失败'
  } finally {
    loading.value = false
  }
}

async function startScan() {
  starting.value = true
  error.value = ''
  try {
    const result: any = await api.post('/scans', form)
    task.value = { id: result.taskId, status: result.status }
    startPolling()
  } catch (e: any) {
    error.value = e.message || '扫描启动失败'
  } finally {
    starting.value = false
  }
}

async function poll() {
  if (!task.value?.id) return
  try {
    task.value = await api.get(`/scans/${task.value.id}`)
    if (!running.value) {
      stopPolling()
      await loadRows()
    }
  } catch (e: any) {
    error.value = e.message || '扫描状态读取失败'
    stopPolling()
  }
}
function startPolling() { stopPolling(); poll(); timer=window.setInterval(poll,2000) }
function stopPolling() { if(timer) window.clearInterval(timer); timer=undefined }
function weight(value: any) { return `${(Number(value || 0)*100).toFixed(0)}%` }
function scoreClass(score: any) { const n=Number(score); return n>=75?'strong':n>=60?'watch':'weak' }

onMounted(loadRows)
onUnmounted(stopPolling)
</script>

<template>
  <h1 class="page-title">全市场智能选股</h1>

  <div class="card scan-toolbar">
    <div class="form-row">
      <label>扫描数量</label><input v-model.number="form.scanLimit" type="number" min="0" step="100">
      <label>批次</label><input v-model.number="form.batchSize" type="number" min="2" max="30">
      <label>排名</label><input v-model.number="form.resultLimit" type="number" min="10" max="200">
      <button class="btn" :disabled="starting || running" @click="startScan">
        {{ running ? `扫描中 ${progress}%` : starting ? '启动中...' : '开始扫描' }}
      </button>
      <button class="btn secondary" :disabled="loading" @click="loadRows">刷新结果</button>
    </div>
    <div v-if="running" class="progress-track"><div class="progress-value" :style="{width:progress+'%'}"></div></div>
    <p class="muted">{{ task.message || '结果来自最近一次完成的全市场扫描。0代表扫描全部已同步股票。' }}</p>
  </div>

  <div class="card" style="margin-top:12px; overflow:auto">
    <table class="table wide-table">
      <thead><tr><th>排名</th><th>代码</th><th>名称</th><th>评分</th><th>信号</th><th>风险</th><th>现价</th><th>买入区间</th><th>止损</th><th>目标1</th><th>仓位</th><th>数据源</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="row.symbol">
          <td>{{ row.rank_no }}</td>
          <td>{{ row.symbol }}</td>
          <td>{{ row.name }}</td>
          <td><span class="score" :class="scoreClass(row.score)">{{ row.score }}</span></td>
          <td>{{ row.signal_level }}</td>
          <td>{{ row.risk_level }}</td>
          <td>{{ row.latest_close }}</td>
          <td>{{ row.buy_low }}–{{ row.buy_high }}</td>
          <td class="down">{{ row.stop_loss }}</td>
          <td class="up">{{ row.target1 }}</td>
          <td>{{ weight(row.suggested_weight) }}</td>
          <td>{{ row.data_source }}</td>
          <td class="actions">
            <router-link :to="`/ai?symbol=${row.symbol}`">AI分析</router-link>
            <router-link :to="`/backtest?symbol=${row.symbol}`">回测</router-link>
          </td>
        </tr>
        <tr v-if="!loading && rows.length===0"><td colspan="13" class="muted">暂无扫描结果，请先同步股票列表并启动扫描。</td></tr>
      </tbody>
    </table>
  </div>
  <p v-if="error" class="error">{{ error }}</p>
</template>

<style scoped>
.scan-toolbar label { color:#7d8da5; font-size:12px; }
.scan-toolbar input { width:90px; }
.progress-track { height:7px; background:#18263a; border-radius:99px; overflow:hidden; }
.progress-value { height:100%; background:#1677ff; transition:width .2s; }
.wide-table { min-width:1350px; }
.score { display:inline-grid; place-items:center; min-width:36px; padding:3px 6px; border-radius:3px; background:#25344a; }
.score.strong { color:#ff6874; background:#45232c; }
.score.watch { color:#f0b84a; background:#44371f; }
.score.weak { color:#4ade9a; background:#173d32; }
.actions { white-space:nowrap; }
.actions a { color:#5da9ff; text-decoration:none; margin-right:9px; }
</style>
