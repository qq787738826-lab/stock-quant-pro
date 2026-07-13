<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api } from '../api'

type Task = {
  id?: number
  status?: string
  total_symbols?: number
  processed_symbols?: number
  success_symbols?: number
  failed_symbols?: number
  selected_count?: number
  message?: string
  started_at?: string
  finished_at?: string
}

type Overview = {
  securities: number
  dailyBars: number
  cachedSymbols: number
  latestBarDate?: string
  latestTask?: Task
}

const overview = ref<Overview>({ securities: 0, dailyBars: 0, cachedSymbols: 0 })
const task = ref<Task>({})
const scanForm = reactive({ scanLimit: 200, batchSize: 12, resultLimit: 50 })
const syncing = ref(false)
const starting = ref(false)
const error = ref('')
const message = ref('')
let timer: number | undefined

const progress = computed(() => {
  const total = Number(task.value.total_symbols || 0)
  const processed = Number(task.value.processed_symbols || 0)
  return total > 0 ? Math.min(100, Math.round(processed * 100 / total)) : 0
})

const running = computed(() => ['QUEUED', 'RUNNING'].includes(task.value.status || ''))

function friendlyError(e: any, fallback: string) {
  const raw = String(e?.message || '')
  if (raw === 'Network Error') {
    return '无法连接Java后端。请确认IDEA中的 QuantServerApplication 已启动，并检查 http://127.0.0.1:8080/api/health'
  }
  return raw || fallback
}

async function loadOverview() {
  overview.value = await api.get('/data/overview') as unknown as Overview
  if (overview.value.latestTask) task.value = overview.value.latestTask
}

async function syncUniverse() {
  syncing.value = true
  error.value = ''
  message.value = ''
  try {
    const result: any = await api.post('/data/universe/sync')
    message.value = `股票列表同步完成，共 ${result.count} 只沪深主板股票`
    await loadOverview()
  } catch (e: any) {
    error.value = friendlyError(e, '股票列表同步失败')
  } finally {
    syncing.value = false
  }
}

async function startScan() {
  starting.value = true
  error.value = ''
  message.value = ''
  try {
    const payload = {
      scanLimit: Number(scanForm.scanLimit || 0),
      batchSize: Number(scanForm.batchSize || 12),
      resultLimit: Number(scanForm.resultLimit || 50),
    }
    const result: any = await api.post('/scans', payload)
    task.value = { id: result.taskId, status: result.status }
    message.value = `扫描任务 #${result.taskId} 已启动`
    beginPolling()
  } catch (e: any) {
    error.value = friendlyError(e, '扫描任务启动失败')
  } finally {
    starting.value = false
  }
}

async function pollTask() {
  if (!task.value.id) {
    await loadOverview()
    return
  }
  try {
    task.value = await api.get(`/scans/${task.value.id}`) as unknown as Task
    if (!running.value) {
      stopPolling()
      await loadOverview()
    }
  } catch (e: any) {
    error.value = friendlyError(e, '任务状态读取失败')
    stopPolling()
  }
}

function beginPolling() {
  stopPolling()
  pollTask()
  timer = window.setInterval(pollTask, 2000)
}

function stopPolling() {
  if (timer) window.clearInterval(timer)
  timer = undefined
}

onMounted(async () => {
  try {
    await loadOverview()
    if (running.value) beginPolling()
  } catch (e: any) {
    error.value = friendlyError(e, '数据中心加载失败')
  }
})
onUnmounted(stopPolling)
</script>

<template>
  <h1 class="page-title">数据管理中心</h1>

  <div class="grid4">
    <div class="card metric"><label>主板股票</label><strong>{{ overview.securities }}</strong></div>
    <div class="card metric"><label>本地K线</label><strong>{{ Number(overview.dailyBars || 0).toLocaleString() }}</strong></div>
    <div class="card metric"><label>已缓存股票</label><strong>{{ overview.cachedSymbols }}</strong></div>
    <div class="card metric"><label>最新交易日</label><strong class="small-metric">{{ overview.latestBarDate || '暂无' }}</strong></div>
  </div>

  <div class="section-grid">
    <div class="card">
      <h3>全市场股票列表</h3>
      <p class="muted">同步沪深主板，自动排除创业板、科创板、北交所、ST和退市股票。</p>
      <button class="btn" :disabled="syncing || running" @click="syncUniverse">
        {{ syncing ? '同步中...' : '同步股票列表' }}
      </button>
    </div>

    <div class="card">
      <h3>扫描参数</h3>
      <div class="form-column">
        <label>扫描数量（0代表全部）</label>
        <input v-model.number="scanForm.scanLimit" type="number" min="0" step="100">
        <label>单批数量</label>
        <input v-model.number="scanForm.batchSize" type="number" min="2" max="30">
        <label>保留排名数量</label>
        <input v-model.number="scanForm.resultLimit" type="number" min="10" max="200">
      </div>
      <button class="btn" :disabled="starting || running" @click="startScan">
        {{ running ? '扫描进行中' : starting ? '启动中...' : '启动全市场扫描' }}
      </button>
    </div>
  </div>

  <div class="card" style="margin-top:12px">
    <div class="task-header">
      <h3>最新扫描任务 <span v-if="task.id">#{{ task.id }}</span></h3>
      <span class="status-tag" :class="String(task.status || '').toLowerCase()">{{ task.status || '暂无任务' }}</span>
    </div>
    <div class="progress-track"><div class="progress-value" :style="{ width: progress + '%' }"></div></div>
    <div class="task-grid">
      <span>进度：{{ progress }}%</span>
      <span>已处理：{{ task.processed_symbols || 0 }} / {{ task.total_symbols || 0 }}</span>
      <span>成功：{{ task.success_symbols || 0 }}</span>
      <span>失败：{{ task.failed_symbols || 0 }}</span>
      <span>候选：{{ task.selected_count || 0 }}</span>
    </div>
    <p class="muted">{{ task.message || '同步股票列表后即可开始扫描。首次全市场扫描耗时较长，后续优先使用本地K线缓存。' }}</p>
  </div>

  <p v-if="message" class="success">{{ message }}</p>
  <p v-if="error" class="error">{{ error }}</p>
</template>

<style scoped>
.small-metric { font-size: 17px !important; }
.form-column { display:grid; grid-template-columns: 1fr 140px; gap:8px 12px; align-items:center; margin-bottom:14px; }
.form-column label { color:#7d8da5; font-size:12px; }
.form-column input { background:#101e31; border:1px solid #2a3e59; color:white; border-radius:4px; padding:8px; }
.task-header { display:flex; align-items:center; justify-content:space-between; }
.status-tag { padding:3px 9px; border-radius:3px; background:#25344a; color:#aab8ca; font-size:12px; }
.status-tag.running, .status-tag.queued { background:#173759; color:#5da9ff; }
.status-tag.completed { background:#173d32; color:#4ade9a; }
.status-tag.failed { background:#492329; color:#ff7b86; }
.progress-track { height:8px; background:#18263a; border-radius:99px; overflow:hidden; margin:12px 0; }
.progress-value { height:100%; background:#1677ff; transition:width .25s; }
.task-grid { display:grid; grid-template-columns:repeat(5,1fr); gap:10px; color:#a8b5c8; font-size:12px; }
</style>
