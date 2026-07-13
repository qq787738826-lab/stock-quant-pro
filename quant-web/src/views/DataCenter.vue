<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api } from '../api'

type Task = {
  id?: number
  status?: string
  scan_type?: string
  official?: boolean
  requested_limit?: number
  batch_size?: number
  result_limit?: number
  total_symbols?: number
  processed_symbols?: number
  success_symbols?: number
  failed_symbols?: number
  selected_count?: number
  trade_date?: string
  duration_ms?: number
  unresolved_failures?: number
  message?: string
  started_at?: string
  finished_at?: string
}

type UpdateTask = {
  id?: number
  status?: string
  requested_limit?: number
  batch_size?: number
  total_symbols?: number
  processed_symbols?: number
  success_symbols?: number
  failed_symbols?: number
  inserted_bars?: number
  latest_trade_date?: string
  message?: string
}

type Overview = {
  securities: number
  dailyBars: number
  cachedSymbols: number
  latestBarDate?: string
  latestTask?: Task
  latestOfficialTask?: Task
  latestUpdateTask?: UpdateTask
}

const overview = ref<Overview>({ securities: 0, dailyBars: 0, cachedSymbols: 0 })
const task = ref<Task>({})
const updateTask = ref<UpdateTask>({})
const taskHistory = ref<Task[]>([])
const scanFailures = ref<any[]>([])
const updateFailures = ref<any[]>([])

const scanForm = reactive({ scanLimit: 0, batchSize: 8, resultLimit: 50 })
const updateForm = reactive({ updateLimit: 0, batchSize: 8 })
const syncing = ref(false)
const starting = ref(false)
const updating = ref(false)
const retrying = ref(false)
const error = ref('')
const message = ref('')
let scanTimer: number | undefined
let updateTimer: number | undefined

const scanRunning = computed(() => ['QUEUED', 'RUNNING'].includes(task.value.status || ''))
const updateRunning = computed(() => ['QUEUED', 'RUNNING'].includes(updateTask.value.status || ''))

const scanProgress = computed(() => {
  const total = Number(task.value.total_symbols || 0)
  const processed = Number(task.value.processed_symbols || 0)
  return total > 0 ? Math.min(100, Math.round(processed * 100 / total)) : 0
})

const updateProgress = computed(() => {
  const total = Number(updateTask.value.total_symbols || 0)
  const processed = Number(updateTask.value.processed_symbols || 0)
  return total > 0 ? Math.min(100, Math.round(processed * 100 / total)) : 0
})

function friendlyError(e: any, fallback: string) {
  const raw = String(e?.message || '')
  if (raw === 'Network Error') {
    return '无法连接Java后端。请确认 QuantServerApplication 已启动，并检查 http://127.0.0.1:8080/api/health'
  }
  return raw || fallback
}

function scanTypeLabel(row: Task) {
  if (row.scan_type === 'FULL') return '全市场'
  if (row.scan_type === 'RETRY') return '失败重试'
  return `测试${row.requested_limit || 0}只`
}

function duration(value: any) {
  const ms = Number(value || 0)
  if (!ms) return '--'
  const seconds = Math.round(ms / 1000)
  return seconds >= 60 ? `${Math.floor(seconds / 60)}分${seconds % 60}秒` : `${seconds}秒`
}

async function loadOverview() {
  overview.value = await api.get('/data/overview') as unknown as Overview
  task.value = overview.value.latestTask || {}
  updateTask.value = overview.value.latestUpdateTask || {}
}

async function loadHistory() {
  taskHistory.value = await api.get('/scans/history', { params: { limit: 20 } }) as unknown as Task[]
}

async function loadScanFailures(taskId?: number) {
  scanFailures.value = taskId
    ? await api.get(`/scans/${taskId}/failures`) as unknown as any[]
    : []
}

async function loadUpdateFailures(taskId?: number) {
  updateFailures.value = taskId
    ? await api.get(`/data/updates/${taskId}/failures`) as unknown as any[]
    : []
}

async function viewTask(row: Task) {
  task.value = row
  scanForm.scanLimit = Number(row.requested_limit || 0)
  scanForm.batchSize = Number(row.batch_size || 8)
  scanForm.resultLimit = Number(row.result_limit || 50)
  await loadScanFailures(row.id)
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
    const result: any = await api.post('/scans', {
      scanLimit: Number(scanForm.scanLimit || 0),
      batchSize: Number(scanForm.batchSize || 8),
      resultLimit: Number(scanForm.resultLimit || 50),
    })
    task.value = { id: result.taskId, status: result.status }
    scanFailures.value = []
    message.value = `扫描任务 #${result.taskId} 已启动`
    beginScanPolling()
  } catch (e: any) {
    error.value = friendlyError(e, '扫描任务启动失败')
  } finally {
    starting.value = false
  }
}

async function retryFailures() {
  if (!task.value.id) return
  retrying.value = true
  error.value = ''
  try {
    const result: any = await api.post(`/scans/${task.value.id}/retry`, { batchSize: 6 })
    task.value = { id: result.taskId, status: result.status, scan_type: 'RETRY' }
    scanFailures.value = []
    message.value = `失败重试任务 #${result.taskId} 已启动`
    beginScanPolling()
  } catch (e: any) {
    error.value = friendlyError(e, '失败股票重试启动失败')
  } finally {
    retrying.value = false
  }
}

async function startUpdate() {
  updating.value = true
  error.value = ''
  message.value = ''
  try {
    const result: any = await api.post('/data/updates', {
      updateLimit: Number(updateForm.updateLimit || 0),
      batchSize: Number(updateForm.batchSize || 8),
    })
    updateTask.value = { id: result.taskId, status: result.status }
    updateFailures.value = []
    message.value = `行情增量更新任务 #${result.taskId} 已启动`
    beginUpdatePolling()
  } catch (e: any) {
    error.value = friendlyError(e, '行情增量更新启动失败')
  } finally {
    updating.value = false
  }
}

async function pollScan() {
  if (!task.value.id) return
  try {
    task.value = await api.get(`/scans/${task.value.id}`) as unknown as Task
    if (!scanRunning.value) {
      stopScanPolling()
      await Promise.all([loadOverview(), loadHistory(), loadScanFailures(task.value.id)])
    }
  } catch (e: any) {
    error.value = friendlyError(e, '扫描状态读取失败')
    stopScanPolling()
  }
}

async function pollUpdate() {
  if (!updateTask.value.id) return
  try {
    updateTask.value = await api.get(`/data/updates/${updateTask.value.id}`) as unknown as UpdateTask
    if (!updateRunning.value) {
      stopUpdatePolling()
      await Promise.all([loadOverview(), loadUpdateFailures(updateTask.value.id)])
    }
  } catch (e: any) {
    error.value = friendlyError(e, '行情更新状态读取失败')
    stopUpdatePolling()
  }
}

function beginScanPolling() {
  stopScanPolling()
  pollScan()
  scanTimer = window.setInterval(pollScan, 2000)
}

function beginUpdatePolling() {
  stopUpdatePolling()
  pollUpdate()
  updateTimer = window.setInterval(pollUpdate, 2000)
}

function stopScanPolling() {
  if (scanTimer) window.clearInterval(scanTimer)
  scanTimer = undefined
}

function stopUpdatePolling() {
  if (updateTimer) window.clearInterval(updateTimer)
  updateTimer = undefined
}

onMounted(async () => {
  try {
    await Promise.all([loadOverview(), loadHistory()])
    if (task.value.id) await loadScanFailures(task.value.id)
    if (updateTask.value.id) await loadUpdateFailures(updateTask.value.id)
    if (scanRunning.value) beginScanPolling()
    if (updateRunning.value) beginUpdatePolling()
  } catch (e: any) {
    error.value = friendlyError(e, '数据中心加载失败')
  }
})

onUnmounted(() => {
  stopScanPolling()
  stopUpdatePolling()
})
</script>

<template>
  <h1 class="page-title">数据管理中心</h1>

  <div class="grid4">
    <div class="card metric"><label>主板股票</label><strong>{{ overview.securities }}</strong></div>
    <div class="card metric"><label>本地K线</label><strong>{{ Number(overview.dailyBars || 0).toLocaleString() }}</strong></div>
    <div class="card metric"><label>已缓存股票</label><strong>{{ overview.cachedSymbols }}</strong></div>
    <div class="card metric"><label>最新交易日</label><strong class="small-metric">{{ overview.latestBarDate || '暂无' }}</strong></div>
  </div>

  <div class="section-grid three">
    <div class="card">
      <h3>股票列表</h3>
      <p class="muted">同步沪深主板，自动排除创业板、科创板、北交所、ST和退市股票。</p>
      <button class="btn" :disabled="syncing || scanRunning" @click="syncUniverse">
        {{ syncing ? '同步中...' : '同步股票列表' }}
      </button>
    </div>

    <div class="card">
      <h3>全市场扫描</h3>
      <div class="form-column">
        <label>扫描数量（0代表全部）</label>
        <input v-model.number="scanForm.scanLimit" type="number" min="0" step="100">
        <label>单批数量</label>
        <input v-model.number="scanForm.batchSize" type="number" min="2" max="30">
        <label>保留排名数量</label>
        <input v-model.number="scanForm.resultLimit" type="number" min="10" max="200">
      </div>
      <button class="btn" :disabled="starting || scanRunning" @click="startScan">
        {{ scanRunning ? '扫描进行中' : starting ? '启动中...' : '启动扫描' }}
      </button>
    </div>

    <div class="card">
      <h3>行情增量更新</h3>
      <p class="muted">只更新最近90日并按主键覆盖，不清空现有历史K线。</p>
      <div class="form-column">
        <label>更新数量（0代表全部）</label>
        <input v-model.number="updateForm.updateLimit" type="number" min="0" step="100">
        <label>单批数量</label>
        <input v-model.number="updateForm.batchSize" type="number" min="2" max="30">
      </div>
      <button class="btn" :disabled="updating || updateRunning" @click="startUpdate">
        {{ updateRunning ? '更新进行中' : updating ? '启动中...' : '更新最新行情' }}
      </button>
    </div>
  </div>

  <div class="card task-card">
    <div class="task-header">
      <h3>扫描任务 <span v-if="task.id">#{{ task.id }} · {{ scanTypeLabel(task) }}</span></h3>
      <span class="status-tag" :class="String(task.status || '').toLowerCase()">
        {{ task.status || '暂无任务' }}
      </span>
    </div>
    <div class="progress-track"><div class="progress-value" :style="{ width: scanProgress + '%' }"></div></div>
    <div class="task-grid">
      <span>进度：{{ scanProgress }}%</span>
      <span>已处理：{{ task.processed_symbols || 0 }} / {{ task.total_symbols || 0 }}</span>
      <span>成功：{{ task.success_symbols || 0 }}</span>
      <span>失败：{{ task.failed_symbols || 0 }}</span>
      <span>严格候选：{{ task.selected_count || 0 }}</span>
      <span>交易日：{{ task.trade_date || '--' }}</span>
    </div>
    <p class="muted">{{ task.message || '同步股票列表后即可开始扫描。' }}</p>
    <button
      v-if="Number(task.failed_symbols || 0) > 0 && !scanRunning"
      class="btn secondary"
      :disabled="retrying"
      @click="retryFailures"
    >
      {{ retrying ? '启动中...' : `重试失败股票（${task.failed_symbols}）` }}
    </button>
  </div>

  <div class="card task-card">
    <div class="task-header">
      <h3>行情更新任务 <span v-if="updateTask.id">#{{ updateTask.id }}</span></h3>
      <span class="status-tag" :class="String(updateTask.status || '').toLowerCase()">
        {{ updateTask.status || '暂无任务' }}
      </span>
    </div>
    <div class="progress-track"><div class="progress-value update" :style="{ width: updateProgress + '%' }"></div></div>
    <div class="task-grid">
      <span>进度：{{ updateProgress }}%</span>
      <span>已处理：{{ updateTask.processed_symbols || 0 }} / {{ updateTask.total_symbols || 0 }}</span>
      <span>成功：{{ updateTask.success_symbols || 0 }}</span>
      <span>失败：{{ updateTask.failed_symbols || 0 }}</span>
      <span>入库K线：{{ Number(updateTask.inserted_bars || 0).toLocaleString() }}</span>
      <span>最新日：{{ updateTask.latest_trade_date || '--' }}</span>
    </div>
    <p class="muted">{{ updateTask.message || '建议每个交易日收盘后执行一次。' }}</p>
  </div>

  <div class="section-grid">
    <div class="card table-card">
      <h3>最近扫描历史</h3>
      <table class="table">
        <thead>
          <tr><th>任务</th><th>类型</th><th>状态</th><th>范围</th><th>成功/失败</th><th>候选</th><th>交易日</th><th>耗时</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="row in taskHistory" :key="row.id">
            <td>#{{ row.id }}</td>
            <td>{{ scanTypeLabel(row) }} <span v-if="row.official" class="official">正式</span></td>
            <td>{{ row.status }}</td>
            <td>{{ row.total_symbols || row.requested_limit || 0 }}</td>
            <td>{{ row.success_symbols || 0 }} / {{ row.failed_symbols || 0 }}</td>
            <td>{{ row.selected_count || 0 }}</td>
            <td>{{ row.trade_date || '--' }}</td>
            <td>{{ duration(row.duration_ms) }}</td>
            <td><button class="link-btn" @click="viewTask(row)">查看</button></td>
          </tr>
          <tr v-if="taskHistory.length === 0"><td colspan="9" class="muted">暂无历史任务</td></tr>
        </tbody>
      </table>
    </div>

    <div class="card failure-card">
      <h3>当前任务失败明细</h3>
      <div class="failure-list">
        <div v-for="row in scanFailures" :key="row.id" class="failure-item">
          <b>{{ row.symbol }} {{ row.name }}</b>
          <span>{{ row.error_message }}</span>
        </div>
        <div v-if="scanFailures.length === 0" class="muted">当前任务没有失败明细</div>
      </div>
      <h3 class="sub-title">行情更新失败</h3>
      <div class="failure-list small">
        <div v-for="row in updateFailures" :key="row.id" class="failure-item">
          <b>{{ row.symbol }} {{ row.name }}</b>
          <span>{{ row.error_message }}</span>
        </div>
        <div v-if="updateFailures.length === 0" class="muted">暂无更新失败记录</div>
      </div>
    </div>
  </div>

  <p v-if="message" class="success">{{ message }}</p>
  <p v-if="error" class="error">{{ error }}</p>
</template>

<style scoped>
.small-metric { font-size: 17px !important; }
.section-grid.three { grid-template-columns: repeat(3, 1fr); }
.form-column { display:grid; grid-template-columns:1fr 130px; gap:8px 10px; align-items:center; margin-bottom:12px; }
.form-column label { color:#7d8da5; font-size:12px; }
.form-column input { background:#101e31; border:1px solid #2a3e59; color:white; border-radius:4px; padding:8px; }
.task-card { margin-top:12px; }
.task-header { display:flex; align-items:center; justify-content:space-between; }
.status-tag { padding:3px 9px; border-radius:3px; background:#25344a; color:#aab8ca; font-size:12px; }
.status-tag.running, .status-tag.queued { background:#173759; color:#5da9ff; }
.status-tag.completed { background:#173d32; color:#4ade9a; }
.status-tag.failed { background:#492329; color:#ff7b86; }
.progress-track { height:8px; background:#18263a; border-radius:99px; overflow:hidden; margin:12px 0; }
.progress-value { height:100%; background:#1677ff; transition:width .25s; }
.progress-value.update { background:#36b37e; }
.task-grid { display:grid; grid-template-columns:repeat(6,1fr); gap:10px; color:#a8b5c8; font-size:12px; }
.table-card { overflow:auto; }
.official { color:#4ade9a; border:1px solid #276a51; border-radius:3px; padding:1px 4px; margin-left:3px; font-size:10px; }
.link-btn { color:#5da9ff; background:none; border:0; cursor:pointer; }
.failure-list { max-height:220px; overflow:auto; }
.failure-list.small { max-height:120px; }
.failure-item { padding:8px 0; border-bottom:1px solid #1b2b40; }
.failure-item b { display:block; color:#ff8a94; font-size:12px; }
.failure-item span { display:block; color:#7d8da5; font-size:11px; margin-top:3px; word-break:break-all; }
.sub-title { margin-top:18px !important; }
@media(max-width:1400px) {
  .section-grid.three { grid-template-columns:1fr; }
  .task-grid { grid-template-columns:repeat(3,1fr); }
}
</style>
