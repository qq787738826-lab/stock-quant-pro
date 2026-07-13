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
  message?: string
}

type ValidationTask = {
  id?: number
  status?: string
  scan_task_id?: number
  top_n?: number
  max_holding_days?: number
  total_symbols?: number
  processed_symbols?: number
  success_symbols?: number
  failed_symbols?: number
  avg_total_return?: number
  avg_win_rate?: number
  avg_max_drawdown?: number
  positive_strategy_count?: number
  message?: string
}

const rows = ref<any[]>([])
const tasks = ref<Task[]>([])
const task = ref<Task>({})
const selectedTaskId = ref<number | undefined>()
const failures = ref<any[]>([])
const loading = ref(false)
const starting = ref(false)
const retrying = ref(false)
const error = ref('')
const showEligibleOnly = ref(true)

const form = reactive({ scanLimit: 0, batchSize: 8, resultLimit: 50 })
const validationForm = reactive({ topN: 20, maxHoldingDays: 10 })
const validationTask = ref<ValidationTask>({})
const validationRows = ref<any[]>([])
const validating = ref(false)
const planningSymbol = ref('')
const planMessage = ref('')

let scanTimer: number | undefined
let validationTimer: number | undefined

const running = computed(() => ['QUEUED', 'RUNNING'].includes(task.value?.status || ''))
const validationRunning = computed(() => ['QUEUED', 'RUNNING'].includes(validationTask.value?.status || ''))
const legacyTask = computed(() => Boolean(task.value?.id) && !task.value?.trade_date)

const progress = computed(() => {
  const total = Number(task.value?.total_symbols || 0)
  return total ? Math.round(Number(task.value?.processed_symbols || 0) * 100 / total) : 0
})

const validationProgress = computed(() => {
  const total = Number(validationTask.value?.total_symbols || 0)
  return total ? Math.round(Number(validationTask.value?.processed_symbols || 0) * 100 / total) : 0
})

function scanTypeLabel(row: Task) {
  if (row.scan_type === 'FULL') return '全市场扫描'
  if (row.scan_type === 'RETRY') return '失败重试'
  return `测试扫描 ${row.requested_limit || 0}只`
}

function weight(value: any) {
  return `${(Number(value || 0) * 100).toFixed(0)}%`
}

function pct(value: any) {
  const number = Number(value || 0) * 100
  return `${number >= 0 ? '+' : ''}${number.toFixed(2)}%`
}

function rawPct(value: any) {
  const number = Number(value || 0)
  return `${number >= 0 ? '+' : ''}${number.toFixed(2)}%`
}

function amount(value: any) {
  const number = Number(value || 0)
  if (number >= 100_000_000) return `${(number / 100_000_000).toFixed(2)}亿`
  if (number >= 10_000) return `${(number / 10_000).toFixed(0)}万`
  return number.toFixed(0)
}

function scoreClass(score: any) {
  const number = Number(score)
  return number >= 75 ? 'strong' : number >= 60 ? 'watch' : 'weak'
}

function resultClass(value: any) {
  return Number(value || 0) >= 0 ? 'up' : 'down'
}

function parseJsonValue(value: any, fallback: any) {
  if (value == null) return fallback
  if (Array.isArray(value)) return value
  if (typeof value === 'object' && value.value != null) {
    return parseJsonValue(value.value, fallback)
  }
  if (typeof value === 'object') return value
  if (typeof value !== 'string') return fallback

  try {
    return JSON.parse(value)
  } catch {
    return fallback
  }
}

function normalizeResult(row: any) {
  const metrics = parseJsonValue(row?.metrics, {})
  return {
    ...row,
    metrics,
    filter_reasons: parseJsonValue(row?.filter_reasons, []),
    bullish: parseJsonValue(row?.bullish, []),
    bearish: parseJsonValue(row?.bearish, []),
    return_5_pct: row?.return_5_pct ?? metrics?.return5Pct ?? null,
    return_20_pct: row?.return_20_pct ?? metrics?.return20Pct ?? null,
    rsi14: row?.rsi14 ?? metrics?.rsi14 ?? null,
    atr14_pct: row?.atr14_pct ?? metrics?.atr14Pct ?? null,
    volume_ratio20: row?.volume_ratio20 ?? metrics?.volumeRatio20 ?? null,
    breakout20: row?.breakout20 ?? metrics?.breakout20 ?? false,
  }
}

function normalizeResults(value: any) {
  return Array.isArray(value) ? value.map(normalizeResult) : []
}

function filterReasonTitle(row: any) {
  const reasons = parseJsonValue(row?.filter_reasons, [])
  return Array.isArray(reasons) ? reasons.join('；') : ''
}

function taskLabel(row: Task) {
  const official = row.official ? '｜正式' : ''
  return `#${row.id}｜${scanTypeLabel(row)}｜${row.total_symbols || row.requested_limit || 0}只${official}`
}

async function loadTaskHistory() {
  tasks.value = await api.get('/scans/history', { params: { limit: 50 } }) as unknown as Task[]
}

async function resolveDefaultTask() {
  const official: any = await api.get('/scans/latest-official-task')
  if (official?.id) return official as Task
  const latest: any = await api.get('/scans/latest-task')
  return latest || {}
}

async function loadTask(taskId: number) {
  loading.value = true
  error.value = ''
  try {
    task.value = await api.get(`/scans/${taskId}`) as unknown as Task
    selectedTaskId.value = taskId

    // 旧版本扫描没有严格候选字段，自动切换为查看全部结果。
    if (!task.value.trade_date) {
      showEligibleOnly.value = false
    }

    const resultRows = await api.get(`/scans/${taskId}/results`, {
      params: { limit: 200, eligibleOnly: showEligibleOnly.value },
    })
    rows.value = normalizeResults(resultRows)
    failures.value = await api.get(`/scans/${taskId}/failures`) as unknown as any[]
    const latestValidation: any = await api.get(`/scans/${taskId}/backtests/latest`)
    validationTask.value = latestValidation || {}
    if (validationTask.value.id) {
      validationRows.value = await api.get(`/scan-backtests/${validationTask.value.id}/results`) as unknown as any[]
      if (validationRunning.value) startValidationPolling()
    } else {
      validationRows.value = []
    }
    if (running.value) startScanPolling()
  } catch (e: any) {
    error.value = e.message || '候选结果加载失败'
  } finally {
    loading.value = false
  }
}

async function loadRows() {
  await loadTaskHistory()
  const defaultTask = await resolveDefaultTask()
  if (defaultTask?.id) {
    await loadTask(Number(defaultTask.id))
  } else {
    task.value = {}
    rows.value = []
  }
}

async function changeTask() {
  if (selectedTaskId.value) await loadTask(selectedTaskId.value)
}

async function toggleEligible() {
  if (!task.value.id) return
  if (legacyTask.value) {
    showEligibleOnly.value = false
    return
  }

  const resultRows = await api.get(`/scans/${task.value.id}/results`, {
    params: { limit: 200, eligibleOnly: showEligibleOnly.value },
  })
  rows.value = normalizeResults(resultRows)
}

async function startScan() {
  starting.value = true
  error.value = ''
  try {
    const result: any = await api.post('/scans', form)
    task.value = { id: result.taskId, status: result.status }
    selectedTaskId.value = result.taskId
    rows.value = []
    failures.value = []
    startScanPolling()
  } catch (e: any) {
    error.value = e.message || '扫描启动失败'
  } finally {
    starting.value = false
  }
}

async function addTradePlan(row: any) {
  if (!task.value.id) return
  planningSymbol.value = row.symbol
  error.value = ''
  planMessage.value = ''
  try {
    const result: any = await api.post('/portfolio/plans/from-scan', {
      scanTaskId: task.value.id,
      symbol: row.symbol,
    })
    planMessage.value = `${row.symbol} ${row.name} 已加入交易计划 #${result.planId}`
  } catch (e: any) {
    error.value = e.message || '加入交易计划失败'
  } finally {
    planningSymbol.value = ''
  }
}

async function retryFailures() {
  if (!task.value.id) return
  retrying.value = true
  error.value = ''
  try {
    const result: any = await api.post(`/scans/${task.value.id}/retry`, { batchSize: 6 })
    task.value = { id: result.taskId, status: result.status, scan_type: 'RETRY' }
    selectedTaskId.value = result.taskId
    rows.value = []
    failures.value = []
    startScanPolling()
  } catch (e: any) {
    error.value = e.message || '失败重试启动失败'
  } finally {
    retrying.value = false
  }
}

async function pollScan() {
  if (!task.value?.id) return
  try {
    task.value = await api.get(`/scans/${task.value.id}`) as unknown as Task
    if (!running.value) {
      stopScanPolling()
      await loadTaskHistory()
      await loadTask(Number(task.value.id))
    }
  } catch (e: any) {
    error.value = e.message || '扫描状态读取失败'
    stopScanPolling()
  }
}

async function startValidation() {
  if (!task.value.id) return
  validating.value = true
  error.value = ''
  try {
    const result: any = await api.post(`/scans/${task.value.id}/backtests`, validationForm)
    validationTask.value = {
      id: result.taskId,
      scan_task_id: task.value.id,
      status: result.status,
    }
    validationRows.value = []
    startValidationPolling()
  } catch (e: any) {
    error.value = e.message || '批量回测启动失败'
  } finally {
    validating.value = false
  }
}

async function pollValidation() {
  if (!validationTask.value.id) return
  try {
    validationTask.value = await api.get(`/scan-backtests/${validationTask.value.id}`) as unknown as ValidationTask
    if (!validationRunning.value) {
      stopValidationPolling()
      validationRows.value = await api.get(`/scan-backtests/${validationTask.value.id}/results`) as unknown as any[]
    }
  } catch (e: any) {
    error.value = e.message || '批量回测状态读取失败'
    stopValidationPolling()
  }
}

function startScanPolling() {
  stopScanPolling()
  pollScan()
  scanTimer = window.setInterval(pollScan, 2000)
}

function stopScanPolling() {
  if (scanTimer) window.clearInterval(scanTimer)
  scanTimer = undefined
}

function startValidationPolling() {
  stopValidationPolling()
  pollValidation()
  validationTimer = window.setInterval(pollValidation, 2000)
}

function stopValidationPolling() {
  if (validationTimer) window.clearInterval(validationTimer)
  validationTimer = undefined
}

onMounted(loadRows)
onUnmounted(() => {
  stopScanPolling()
  stopValidationPolling()
})
</script>

<template>
  <h1 class="page-title">全市场智能选股</h1>

  <div class="card scan-toolbar">
    <div class="toolbar-row">
      <div class="task-select">
        <label>查看扫描任务</label>
        <select v-model.number="selectedTaskId" @change="changeTask">
          <option v-for="row in tasks" :key="row.id" :value="row.id">{{ taskLabel(row) }}</option>
        </select>
      </div>
      <label class="check-row">
        <input v-model="showEligibleOnly" type="checkbox" :disabled="legacyTask" @change="toggleEligible">
        {{ legacyTask ? '历史任务不支持严格候选' : '只看严格候选' }}
      </label>
      <button class="btn secondary" :disabled="loading" @click="loadRows">刷新</button>
    </div>

    <div class="task-summary" v-if="task.id">
      <div>
        <b>任务 #{{ task.id }} · {{ scanTypeLabel(task) }}</b>
        <span v-if="task.official" class="official">正式全市场结果</span>
      </div>
      <div class="summary-grid">
        <span>范围：{{ task.total_symbols || task.requested_limit || 0 }}只</span>
        <span>成功：{{ task.success_symbols || 0 }}</span>
        <span>失败：{{ task.failed_symbols || 0 }}</span>
        <span>{{ legacyTask ? '历史候选' : '严格候选' }}：{{ task.selected_count || 0 }}</span>
        <span>交易日：{{ task.trade_date || '--' }}</span>
        <span>状态：{{ task.status || '--' }}</span>
      </div>
    </div>

    <p v-if="legacyTask" class="legacy-note">该任务来自旧版本，结果仍可查看，但严格候选资格需要使用当前版本重新扫描。</p>

    <div class="scan-form">
      <label>扫描数量</label><input v-model.number="form.scanLimit" type="number" min="0" step="100">
      <label>批次</label><input v-model.number="form.batchSize" type="number" min="2" max="30">
      <label>排名</label><input v-model.number="form.resultLimit" type="number" min="10" max="200">
      <button class="btn" :disabled="starting || running" @click="startScan">
        {{ running ? `扫描中 ${progress}%` : starting ? '启动中...' : '新建扫描' }}
      </button>
      <button
        v-if="Number(task.failed_symbols || 0) > 0 && !running"
        class="btn secondary"
        :disabled="retrying"
        @click="retryFailures"
      >
        {{ retrying ? '启动中...' : `重试失败 ${task.failed_symbols}只` }}
      </button>
    </div>

    <div v-if="running" class="progress-track"><div class="progress-value" :style="{width:progress+'%'}"></div></div>
    <p class="muted">{{ task.message || '默认展示最近一次正式全市场扫描，测试扫描不会覆盖正式结果。' }}</p>
  </div>

  <div class="card result-card">
    <table class="table wide-table">
      <thead>
        <tr>
          <th>排名</th><th>代码</th><th>名称</th><th>评分</th><th>资格</th>
          <th>风险</th><th>现价</th><th>5日</th><th>20日</th><th>RSI</th>
          <th>ATR</th><th>量比</th><th>20日均额</th><th>买入区间</th>
          <th>止损</th><th>目标1</th><th>仓位</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="`${row.task_id}-${row.symbol}`">
          <td>{{ row.rank_no }}</td>
          <td>{{ row.symbol }}</td>
          <td>{{ row.name }}</td>
          <td><span class="score" :class="scoreClass(row.score)">{{ row.score }}</span></td>
          <td>
            <span v-if="row.eligible" class="eligible">通过</span>
            <span v-else-if="filterReasonTitle(row)" class="filtered" :title="filterReasonTitle(row)">过滤</span>
            <span v-else class="legacy-result">历史</span>
          </td>
          <td>{{ row.risk_level }}</td>
          <td>{{ row.latest_close }}</td>
          <td :class="Number(row.return_5_pct || 0)>=0?'up':'down'">{{ rawPct(row.return_5_pct) }}</td>
          <td :class="Number(row.return_20_pct || 0)>=0?'up':'down'">{{ rawPct(row.return_20_pct) }}</td>
          <td>{{ Number(row.rsi14 || 0).toFixed(1) }}</td>
          <td>{{ Number(row.atr14_pct || 0).toFixed(2) }}%</td>
          <td>{{ Number(row.volume_ratio20 || 0).toFixed(2) }}</td>
          <td>{{ amount(row.avg_amount_20) }}</td>
          <td>{{ row.buy_low }}–{{ row.buy_high }}</td>
          <td class="down">{{ row.stop_loss }}</td>
          <td class="up">{{ row.target1 }}</td>
          <td>{{ weight(row.suggested_weight) }}</td>
          <td class="actions">
            <router-link :to="`/ai?symbol=${row.symbol}`">分析</router-link>
            <router-link :to="`/backtest?symbol=${row.symbol}`">回测</router-link>
            <button v-if="row.eligible" class="plan-button" :disabled="planningSymbol===row.symbol" @click="addTradePlan(row)">{{ planningSymbol===row.symbol ? '加入中' : '交易计划' }}</button>
          </td>
        </tr>
        <tr v-if="!loading && rows.length===0">
          <td colspan="18" class="muted">该任务暂无符合当前条件的结果。</td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="validation-grid">
    <div class="card">
      <h3>候选批量历史回测</h3>
      <p class="muted">使用本地K线验证排名靠前股票的历史策略表现，不代表扫描后的未来收益。</p>
      <div class="validation-form">
        <label>回测前N名</label>
        <input v-model.number="validationForm.topN" type="number" min="5" max="50">
        <label>最大持有日</label>
        <input v-model.number="validationForm.maxHoldingDays" type="number" min="2" max="30">
        <button class="btn" :disabled="validating || validationRunning || !task.id" @click="startValidation">
          {{ validationRunning ? `回测中 ${validationProgress}%` : validating ? '启动中...' : '开始批量回测' }}
        </button>
      </div>

      <div v-if="validationTask.id" class="validation-summary">
        <span>任务 #{{ validationTask.id }}</span>
        <span>状态：{{ validationTask.status }}</span>
        <span>成功：{{ validationTask.success_symbols || 0 }}</span>
        <span>失败：{{ validationTask.failed_symbols || 0 }}</span>
        <span>正收益策略：{{ validationTask.positive_strategy_count || 0 }}</span>
        <span>平均收益：{{ pct(validationTask.avg_total_return) }}</span>
        <span>平均胜率：{{ pct(validationTask.avg_win_rate) }}</span>
        <span>平均回撤：{{ pct(validationTask.avg_max_drawdown) }}</span>
      </div>
      <div v-if="validationRunning" class="progress-track">
        <div class="progress-value validation" :style="{width:validationProgress+'%'}"></div>
      </div>
    </div>

    <div class="card failures">
      <h3>扫描失败明细</h3>
      <div v-if="failures.length===0" class="muted">当前任务没有失败股票</div>
      <div v-for="row in failures" :key="row.id" class="failure">
        <b>{{ row.symbol }} {{ row.name }}</b>
        <span>{{ row.error_message }}</span>
      </div>
    </div>
  </div>

  <div v-if="validationRows.length" class="card result-card">
    <h3>批量回测结果</h3>
    <table class="table">
      <thead>
        <tr><th>扫描排名</th><th>代码</th><th>名称</th><th>扫描分</th><th>总收益</th><th>最大回撤</th><th>胜率</th><th>盈亏比</th><th>交易数</th><th>状态</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in validationRows" :key="row.id">
          <td>{{ row.scan_rank }}</td>
          <td>{{ row.symbol }}</td>
          <td>{{ row.name }}</td>
          <td>{{ row.scan_score }}</td>
          <td :class="resultClass(row.total_return)">{{ pct(row.total_return) }}</td>
          <td class="down">{{ pct(row.max_drawdown) }}</td>
          <td>{{ pct(row.win_rate) }}</td>
          <td>{{ Number(row.profit_loss_ratio || 0).toFixed(2) }}</td>
          <td>{{ row.trade_count || 0 }}</td>
          <td :title="row.error_message || ''">{{ row.status }}</td>
        </tr>
      </tbody>
    </table>
  </div>

  <p v-if="planMessage" class="success">{{ planMessage }}</p>
  <p v-if="error" class="error">{{ error }}</p>
</template>

<style scoped>
.scan-toolbar { display:grid; gap:12px; }
.toolbar-row { display:flex; align-items:end; gap:14px; }
.task-select { display:grid; gap:5px; min-width:480px; }
.task-select label, .scan-form label, .validation-form label { color:#7d8da5; font-size:12px; }
.task-select select, .scan-form input, .validation-form input {
  background:#101e31; border:1px solid #2a3e59; color:white; border-radius:4px; padding:8px;
}
.check-row { display:flex; align-items:center; gap:6px; color:#aab8ca; font-size:12px; }
.task-summary { border:1px solid #223149; background:#0b1727; padding:10px; border-radius:4px; }
.task-summary > div:first-child { display:flex; align-items:center; gap:8px; }
.official { color:#4ade9a; border:1px solid #276a51; border-radius:3px; padding:2px 6px; font-size:10px; }
.summary-grid { display:grid; grid-template-columns:repeat(6,1fr); gap:8px; color:#8192aa; font-size:11px; margin-top:8px; }
.scan-form { display:flex; align-items:center; gap:8px; }
.scan-form input { width:86px; }
.progress-track { height:7px; background:#18263a; border-radius:99px; overflow:hidden; }
.progress-value { height:100%; background:#1677ff; transition:width .2s; }
.progress-value.validation { background:#36b37e; }
.result-card { margin-top:12px; overflow:auto; }
.wide-table { min-width:1760px; }
.score { display:inline-grid; place-items:center; min-width:36px; padding:3px 6px; border-radius:3px; background:#25344a; }
.score.strong { color:#ff6874; background:#45232c; }
.score.watch { color:#f0b84a; background:#44371f; }
.score.weak { color:#4ade9a; background:#173d32; }
.eligible { color:#4ade9a; }
.filtered { color:#ff7b86; cursor:help; }
.legacy-result { color:#f0b84a; }
.legacy-note { color:#f0b84a; border-left:3px solid #8f6a21; padding-left:10px; margin:0; }
.actions { white-space:nowrap; }
.actions a { color:#5da9ff; text-decoration:none; margin-right:8px; }
.plan-button { border:1px solid #276a51; color:#4ade9a; background:#143226; border-radius:3px; padding:3px 6px; cursor:pointer; font-size:11px; }
.plan-button:disabled { opacity:.5; cursor:wait; }
.validation-grid { display:grid; grid-template-columns:2fr 1fr; gap:12px; margin-top:12px; }
.validation-form { display:flex; align-items:center; gap:8px; }
.validation-form input { width:80px; }
.validation-summary { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; color:#aab8ca; font-size:12px; margin-top:14px; }
.failures { max-height:260px; overflow:auto; }
.failure { padding:7px 0; border-bottom:1px solid #1b2b40; }
.failure b { display:block; color:#ff8a94; font-size:12px; }
.failure span { display:block; color:#7d8da5; font-size:11px; margin-top:3px; word-break:break-all; }
@media(max-width:1450px) {
  .summary-grid { grid-template-columns:repeat(3,1fr); }
  .validation-grid { grid-template-columns:1fr; }
}
</style>
