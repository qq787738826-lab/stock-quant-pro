<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createAgentTask,
  getAgentDecision,
  getAgentEvidence,
  getAgentRuns,
  getAgentTask,
  getAgentVetoes,
} from '../agent-team/api'
import {
  AGENT_NAMES,
  formatJson,
  isTerminalTaskStatus,
  localIsoDate,
  orderAgentRuns,
  uniqueEvidence,
} from '../agent-team/presentation'
import type {
  AgentRun,
  AgentTask,
  CreateAgentTaskRequest,
  Evidence,
  FinalDecision,
  FormalVeto,
} from '../agent-team/types'

const POLL_INTERVAL_MS = 2000
const MAX_POLL_FAILURES = 3

const route = useRoute()
const router = useRouter()
const form = reactive<CreateAgentTaskRequest>({
  symbol: '',
  tradeDate: localIsoDate(),
  ruleVersion: '1.4.0-stage-2b-dq-v1',
  executionMode: 'LOCAL_RULES',
  triggerType: 'MANUAL',
  forceRefresh: false,
})

const task = shallowRef<AgentTask | null>(null)
const runs = shallowRef<AgentRun[]>([])
const evidence = shallowRef<Evidence[]>([])
const decision = shallowRef<FinalDecision | null>(null)
const vetoes = shallowRef<FormalVeto[]>([])
const expandedEvidence = ref(new Set<string>())
const activeTaskId = ref<number | null>(null)
const newlyCreated = ref<boolean | null>(null)
const loading = ref(false)
const creating = ref(false)
const polling = ref(false)
const errorMessage = ref('')

let pollTimer: number | null = null
let pollInFlight = false
let pollFailures = 0
let requestGeneration = 0

const orderedRuns = computed(() => orderAgentRuns(runs.value))
const evidenceResult = computed(() => uniqueEvidence(evidence.value))
const displayedEvidence = computed(() => evidenceResult.value.items)
const duplicateEvidenceWarning = computed(() => {
  const ids = evidenceResult.value.duplicateIds
  return ids.length ? `检测到重复证据ID，已保留首次出现记录：${ids.join('、')}` : ''
})

function safeError(error: unknown, fallback: string): string {
  if (!(error instanceof Error)) return fallback
  const message = error.message.trim()
  if (!message) return fallback
  if (message.includes('不存在') || message.includes('404')) return '智能体任务不存在'
  return message.length <= 200 ? message : fallback
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '暂无'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function displayNumber(value: number | null | undefined): string {
  return value == null ? '暂无' : String(value)
}

function clearPollTimer(): void {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  polling.value = false
  pollFailures = 0
}

function invalidateRequestsAndStopPolling(): number {
  requestGeneration += 1
  clearPollTimer()
  return requestGeneration
}

function isCurrentRequest(generation: number, taskId: number): boolean {
  return generation === requestGeneration && activeTaskId.value === taskId
}

function clearResults(): void {
  task.value = null
  runs.value = []
  evidence.value = []
  decision.value = null
  vetoes.value = []
  expandedEvidence.value = new Set()
}

function parseTaskId(rawTaskId: unknown): number | null | 'invalid' {
  if (rawTaskId == null) return null
  if (typeof rawTaskId !== 'string' || !/^[1-9]\d*$/.test(rawTaskId)) return 'invalid'
  const taskId = Number(rawTaskId)
  return Number.isSafeInteger(taskId) && taskId > 0 ? taskId : 'invalid'
}

async function loadTaskAndRuns(taskId: number, generation = requestGeneration): Promise<AgentTask | null> {
  const [loadedTask, loadedRuns] = await Promise.all([getAgentTask(taskId), getAgentRuns(taskId)])
  if (!isCurrentRequest(generation, taskId)) return null
  task.value = loadedTask
  runs.value = loadedRuns ?? []
  return loadedTask
}

async function loadTerminalResults(taskId: number, generation = requestGeneration): Promise<void> {
  const [loadedEvidence, loadedDecision, loadedVetoes] = await Promise.all([
    getAgentEvidence(taskId),
    getAgentDecision(taskId),
    getAgentVetoes(taskId),
  ])
  if (!isCurrentRequest(generation, taskId)) return
  evidence.value = loadedEvidence ?? []
  decision.value = loadedDecision
  vetoes.value = loadedVetoes ?? []
}

async function pollTick(): Promise<void> {
  if (pollInFlight || activeTaskId.value == null) return
  pollInFlight = true
  const taskId = activeTaskId.value
  const generation = requestGeneration
  try {
    const loadedTask = await loadTaskAndRuns(taskId, generation)
    if (!loadedTask) return
    pollFailures = 0
    errorMessage.value = ''
    if (isTerminalTaskStatus(loadedTask.status)) {
      clearPollTimer()
      try {
        await loadTerminalResults(taskId, generation)
      } catch (error) {
        if (isCurrentRequest(generation, taskId)) {
          errorMessage.value = safeError(error, '任务结果加载失败，请手动重新加载')
        }
      }
    }
  } catch (error) {
    if (!isCurrentRequest(generation, taskId)) return
    pollFailures += 1
    if (pollFailures >= MAX_POLL_FAILURES) {
      clearPollTimer()
      errorMessage.value = '连续三次轮询失败，已停止自动刷新，请手动重新加载'
    } else {
      errorMessage.value = safeError(error, '任务状态刷新失败，将继续重试')
    }
  } finally {
    pollInFlight = false
  }
}

function startPolling(taskId: number, generation: number): void {
  if (pollTimer != null || !isCurrentRequest(generation, taskId)) return
  polling.value = true
  pollFailures = 0
  pollTimer = window.setInterval(() => void pollTick(), POLL_INTERVAL_MS)
}

function validateForm(): string {
  if (!/^\d{6}$/.test(form.symbol)) return '股票代码必须为6位数字'
  if (!form.tradeDate) return '交易日期不能为空'
  const ruleVersion = form.ruleVersion.trim()
  if (!ruleVersion) return '规则版本不能为空'
  if (ruleVersion.length > 64) return '规则版本不能超过64个字符'
  return ''
}

async function createTask(): Promise<void> {
  const validation = validateForm()
  if (validation) {
    errorMessage.value = validation
    return
  }
  invalidateRequestsAndStopPolling()
  clearResults()
  activeTaskId.value = null
  newlyCreated.value = null
  loading.value = false
  errorMessage.value = ''
  creating.value = true
  let generation = requestGeneration
  try {
    if (route.query.taskId != null) {
      await router.replace({ path: '/agent-team' })
    }
    generation = requestGeneration
    const created = await createAgentTask({ ...form, ruleVersion: form.ruleVersion.trim() })
    const taskId = created.task.id
    if (generation !== requestGeneration) return
    activeTaskId.value = taskId
    newlyCreated.value = created.newlyCreated
    task.value = created.task
    await router.replace({ path: '/agent-team', query: { taskId: String(taskId) } })
    if (!isCurrentRequest(generation, taskId)) return
    const loadedTask = await loadTaskAndRuns(taskId, generation)
    if (loadedTask && isTerminalTaskStatus(loadedTask.status)) {
      await loadTerminalResults(taskId, generation)
    } else if (loadedTask) {
      startPolling(taskId, generation)
    }
  } catch (error) {
    if (generation === requestGeneration) {
      errorMessage.value = safeError(error, '创建智能体任务失败')
    }
  } finally {
    creating.value = false
  }
}

async function restoreTask(taskId: number, clearExisting = true): Promise<void> {
  const generation = invalidateRequestsAndStopPolling()
  if (clearExisting) clearResults()
  activeTaskId.value = taskId
  if (clearExisting) newlyCreated.value = null
  errorMessage.value = ''
  loading.value = true
  try {
    const loadedTask = await loadTaskAndRuns(taskId, generation)
    if (!loadedTask) return
    if (isTerminalTaskStatus(loadedTask.status)) {
      await loadTerminalResults(taskId, generation)
    } else {
      startPolling(taskId, generation)
    }
  } catch (error) {
    if (isCurrentRequest(generation, taskId)) {
      errorMessage.value = safeError(error, '加载智能体任务失败')
    }
  } finally {
    if (isCurrentRequest(generation, taskId)) {
      loading.value = false
    }
  }
}

async function reloadCurrentTask(): Promise<void> {
  if (activeTaskId.value == null) return
  await restoreTask(activeTaskId.value, false)
}

function toggleEvidence(evidenceId: string): void {
  const next = new Set(expandedEvidence.value)
  next.has(evidenceId) ? next.delete(evidenceId) : next.add(evidenceId)
  expandedEvidence.value = next
}

function runClass(run: AgentRun): string[] {
  return [
    run.status === 'FAILED' ? 'is-failed' : '',
    run.agentCode === 'DATA_QUALITY' && run.gateStatus === 'BLOCKED' ? 'is-blocked' : '',
    run.agentCode === 'POSITION_RISK' && run.veto ? 'is-risk' : '',
  ]
}

watch(
  () => route.query.taskId,
  (rawTaskId) => {
    const parsedTaskId = parseTaskId(rawTaskId)
    if (parsedTaskId == null) {
      invalidateRequestsAndStopPolling()
      clearResults()
      activeTaskId.value = null
      newlyCreated.value = null
      loading.value = false
      errorMessage.value = ''
      return
    }
    if (parsedTaskId === 'invalid') {
      invalidateRequestsAndStopPolling()
      clearResults()
      activeTaskId.value = null
      newlyCreated.value = null
      loading.value = false
      errorMessage.value = 'URL中的taskId必须是安全的正整数'
      return
    }
    if (activeTaskId.value === parsedTaskId && task.value?.id === parsedTaskId) return
    void restoreTask(parsedTaskId)
  },
  { immediate: true },
)

onBeforeUnmount(invalidateRequestsAndStopPolling)
</script>

<template>
  <div class="agent-workbench" v-loading="loading">
    <section class="panel create-panel">
      <div class="section-heading">
        <div>
          <p class="eyebrow">AGENT TEAM</p>
          <h1>智能体团队工作台</h1>
        </div>
        <el-tag v-if="polling" type="primary">每2秒自动刷新</el-tag>
      </div>

      <el-form :model="form" label-position="top" class="create-form" @submit.prevent="createTask">
        <el-form-item label="股票代码">
          <el-input v-model="form.symbol" maxlength="6" placeholder="请输入6位股票代码" />
        </el-form-item>
        <el-form-item label="交易日期">
          <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="规则版本">
          <el-input v-model="form.ruleVersion" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="执行模式">
          <el-select v-model="form.executionMode"><el-option label="本地规则" value="LOCAL_RULES" /></el-select>
        </el-form-item>
        <el-form-item label="触发类型">
          <el-select v-model="form.triggerType"><el-option label="手动" value="MANUAL" /></el-select>
        </el-form-item>
        <el-form-item label="缓存策略" class="force-refresh">
          <el-checkbox v-model="form.forceRefresh">强制刷新</el-checkbox>
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="creating" :disabled="creating">创建任务</el-button>
      </el-form>
    </section>

    <el-alert v-if="errorMessage" class="page-alert" type="error" show-icon closable :title="errorMessage" @close="errorMessage = ''" />
    <el-alert v-if="duplicateEvidenceWarning" class="page-alert" type="warning" show-icon :closable="false"
      :title="duplicateEvidenceWarning" />

    <section v-if="task" class="panel task-panel">
      <div class="section-heading">
        <div><p class="eyebrow">TASK #{{ task.id }}</p><h2>当前任务</h2></div>
        <div class="heading-actions">
          <el-tag :type="task.status === 'FAILED' ? 'danger' : task.status === 'RUNNING' ? 'primary' : 'info'">{{ task.status }}</el-tag>
          <el-button size="small" :loading="loading" :disabled="loading" @click="reloadCurrentTask">重新加载</el-button>
        </div>
      </div>
      <div class="facts-grid">
        <div><span>股票 / 日期</span><strong>{{ task.symbol }} / {{ task.tradeDate }}</strong></div>
        <div><span>规则版本</span><strong>{{ task.ruleVersion }}</strong></div>
        <div><span>执行 / 触发</span><strong>{{ task.executionMode }} / {{ task.triggerType }}</strong></div>
        <div><span>创建结果</span><strong>{{ newlyCreated == null ? '恢复查询' : newlyCreated ? '新建任务' : '命中已有任务' }}</strong></div>
        <div><span>创建时间</span><strong>{{ formatTime(task.createdAt) }}</strong></div>
        <div><span>开始 / 完成</span><strong>{{ formatTime(task.startedAt) }} / {{ formatTime(task.finishedAt) }}</strong></div>
      </div>
      <el-alert v-if="task.status === 'FAILED'" type="error" show-icon :closable="false" :title="task.errorMessage || '智能体任务执行失败'" />
    </section>

    <section v-if="task" class="workbench-section">
      <div class="section-heading"><div><p class="eyebrow">SIX SPECIALISTS</p><h2>六个专业智能体</h2></div></div>
      <div class="agent-grid">
        <article v-for="run in orderedRuns" :key="run.id" class="agent-card" :class="runClass(run)">
          <header><div><h3>{{ AGENT_NAMES[run.agentCode] }}</h3><code>{{ run.agentCode }}</code></div><el-tag size="small">{{ run.status }}</el-tag></header>
          <div class="score-row"><div><span>评分</span><strong>{{ displayNumber(run.score) }}</strong></div><div><span>置信度</span><strong>{{ displayNumber(run.confidence) }}</strong></div></div>
          <dl>
            <div><dt>runId</dt><dd>{{ run.id }}</dd></div><div><dt>门禁</dt><dd>{{ run.gateStatus ?? '暂无' }}</dd></div>
            <div><dt>结论</dt><dd>{{ run.decision ?? '暂无' }}</dd></div><div><dt>耗时</dt><dd>{{ run.durationMs == null ? '暂无' : `${run.durationMs} ms` }}</dd></div>
          </dl>
          <el-alert v-if="run.agentCode === 'DATA_QUALITY' && run.gateStatus === 'BLOCKED'" title="数据质量阻断（非正式否决）" type="warning" :closable="false" />
          <el-alert v-if="run.agentCode === 'POSITION_RISK' && run.veto" title="该运行声明风险；正式否决以否决记录为准" type="error" :closable="false" />
          <p class="summary">{{ run.summary || '暂无摘要' }}</p>
          <p v-if="run.errorMessage" class="run-error">{{ run.errorMessage }}</p>
          <footer>{{ formatTime(run.startedAt) }} → {{ formatTime(run.finishedAt) }}</footer>
        </article>
      </div>
    </section>

    <section v-if="task" class="panel decision-panel">
      <div class="section-heading"><div><p class="eyebrow">FINAL DECISION</p><h2>总控决策</h2></div><el-tag v-if="decision" :type="decision.vetoed ? 'danger' : 'warning'">{{ decision.decision }}</el-tag></div>
      <div v-if="!decision" class="empty-state">总控决策尚未生成</div>
      <template v-else>
        <el-alert v-if="decision.decision === 'BLOCKED_BY_DATA_QUALITY'" title="因数据质量不足阻断研究，不属于正式风险否决" type="warning" :closable="false" />
        <el-alert v-if="decision.decision === 'REJECTED_BY_VETO'" title="POSITION_RISK 已形成正式风险否决" type="error" :closable="false" />
        <div class="facts-grid decision-facts">
          <div><span>门禁 / 否决</span><strong>{{ decision.gateStatus }} / {{ decision.vetoed ? '是' : '否' }}</strong></div>
          <div><span>评分 / 置信度</span><strong>{{ displayNumber(decision.score) }} / {{ displayNumber(decision.confidence) }}</strong></div>
          <div><span>生成时间</span><strong>{{ formatTime(decision.generatedAt) }}</strong></div>
          <div><span>sourceRunIds</span><strong>{{ (decision.sourceRunIds ?? []).join(', ') || '暂无' }}</strong></div>
          <div><span>vetoIds</span><strong>{{ (decision.vetoIds ?? []).join(', ') || '暂无' }}</strong></div>
        </div>
        <p class="decision-summary">{{ decision.summary }}</p>
        <div v-if="(decision.findings ?? []).length" class="finding-list">
          <article v-for="finding in decision.findings ?? []" :key="finding.findingId">
            <strong>{{ finding.title }}</strong><span>{{ finding.severity }}</span><p>{{ finding.detail }}</p>
          </article>
        </div>
      </template>
    </section>

    <section v-if="task" class="panel veto-panel">
      <div class="section-heading"><div><p class="eyebrow">FORMAL VETOES</p><h2>正式否决</h2></div></div>
      <div v-if="!vetoes.length" class="empty-state">无正式否决</div>
      <article v-for="veto in vetoes" v-else :key="veto.vetoId" class="veto-card">
        <header><strong>{{ veto.vetoCode }}</strong><code>{{ veto.agentCode }}</code></header>
        <p>{{ veto.reason }}</p>
        <div class="facts-grid compact"><div><span>vetoId / taskId / runId</span><strong>{{ veto.vetoId }} / {{ veto.taskId }} / {{ veto.runId }}</strong></div><div><span>证据</span><strong>{{ (veto.evidenceIds ?? []).join(', ') || '暂无' }}</strong></div><div><span>创建时间</span><strong>{{ formatTime(veto.createdAt) }}</strong></div></div>
      </article>
    </section>

    <section v-if="task" class="panel evidence-panel">
      <div class="section-heading"><div><p class="eyebrow">AUTHORITATIVE EVIDENCE</p><h2>权威证据</h2></div><el-tag>{{ displayedEvidence.length }} 条</el-tag></div>
      <div v-if="!displayedEvidence.length" class="empty-state">暂无证据</div>
      <article v-for="item in displayedEvidence" v-else :key="item.evidenceId" class="evidence-card">
        <header><div><strong>{{ item.evidenceId }}</strong><span>{{ item.category }} · {{ item.sourceType }}</span></div><el-button text type="primary" @click="toggleEvidence(item.evidenceId)">{{ expandedEvidence.has(item.evidenceId) ? '收起' : '查看详情' }}</el-button></header>
        <div class="facts-grid compact">
          <div><span>来源</span><strong>{{ item.sourceName }} / {{ item.sourceRef || '暂无' }}</strong></div>
          <div><span>股票 / 日期</span><strong>{{ item.symbol || '暂无' }} / {{ item.tradeDate }}</strong></div>
          <div><span>观察 / 采集</span><strong>{{ formatTime(item.observedAt) }} / {{ formatTime(item.collectedAt) }}</strong></div>
          <div><span>contentHash</span><strong class="hash">{{ item.contentHash }}</strong></div>
        </div>
        <pre v-if="expandedEvidence.has(item.evidenceId)">{{ formatJson(item.fields) }}</pre>
      </article>
    </section>
  </div>
</template>

<style scoped>
.agent-workbench { display: grid; gap: 20px; min-width: 0; }
.panel, .agent-card { border: 1px solid var(--border); background: linear-gradient(145deg, rgba(17,30,52,.96), rgba(11,18,32,.98)); border-radius: 10px; box-shadow: 0 12px 30px rgba(0,0,0,.18); }
.panel { padding: 22px; }
.section-heading, .heading-actions, .agent-card header, .evidence-card header, .veto-card header { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
h1, h2, h3, p { margin-top: 0; } h1 { margin-bottom: 0; font-size: 24px; } h2 { margin-bottom: 0; font-size: 19px; } h3 { margin-bottom: 3px; font-size: 17px; }
.eyebrow { margin-bottom: 5px; color: var(--accent); font-size: 11px; font-weight: 700; letter-spacing: .16em; }
.create-form { display: grid; grid-template-columns: repeat(3, minmax(150px, 1fr)); align-items: end; gap: 0 16px; margin-top: 18px; }
.create-form :deep(.el-date-editor), .create-form :deep(.el-select) { width: 100%; }.create-form :deep(.el-form-item) { margin-bottom: 15px; }.force-refresh { align-self: center; }
.page-alert { margin: 0; }.facts-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-top: 18px; }.facts-grid.compact { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.facts-grid div { min-width: 0; }.facts-grid span, dt, .score-row span { display: block; margin-bottom: 4px; color: var(--muted); font-size: 12px; }.facts-grid strong, dd { overflow-wrap: anywhere; font-size: 13px; }
.workbench-section { min-width: 0; }.workbench-section > .section-heading { margin: 4px 2px 14px; }.agent-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }.agent-card { min-width: 0; padding: 18px; border-top: 3px solid var(--border); }.agent-card.is-blocked { border-top-color: var(--warn); }.agent-card.is-risk, .agent-card.is-failed { border-top-color: var(--danger); }
code { color: var(--muted); font-size: 11px; }.score-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 17px 0; }.score-row > div { padding: 10px; border: 1px solid var(--border); border-radius: 7px; background: rgba(8,14,25,.5); }.score-row strong { font: 700 22px/1 ui-monospace, monospace; color: var(--accent); }
dl { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 0 0 14px; }dl div { min-width: 0; }dd { margin: 0; }.summary, .decision-summary, .run-error { margin: 14px 0; overflow-wrap: anywhere; line-height: 1.65; }.run-error { color: var(--danger); }.agent-card footer { color: var(--muted); font-size: 11px; }
.decision-panel, .veto-panel, .evidence-panel { min-width: 0; }.decision-facts { margin-bottom: 18px; }.finding-list { display: grid; gap: 10px; }.finding-list article, .veto-card, .evidence-card { padding: 15px; border: 1px solid var(--border); border-radius: 8px; background: rgba(8,14,25,.55); }.finding-list span { margin-left: 10px; color: var(--warn); }.finding-list p { margin: 8px 0 0; overflow-wrap: anywhere; }.veto-card, .evidence-card { margin-top: 12px; }.veto-card { border-left: 3px solid var(--danger); }.veto-card p { margin: 13px 0; overflow-wrap: anywhere; }.evidence-card header span { display: block; margin-top: 4px; color: var(--muted); font-size: 12px; }.hash { font-family: ui-monospace, monospace; word-break: break-all; }.evidence-card pre { max-height: 360px; margin: 14px 0 0; padding: 14px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; border-radius: 6px; background: #060b14; color: #b9c7da; font: 12px/1.6 ui-monospace, monospace; }.empty-state { padding: 28px 0 12px; color: var(--muted); text-align: center; }
@media (max-width: 1100px) { .agent-grid, .create-form { grid-template-columns: repeat(2, minmax(0, 1fr)); }.facts-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .panel { padding: 16px; }.agent-grid, .create-form, .facts-grid, .facts-grid.compact { grid-template-columns: 1fr; }.section-heading { align-items: flex-start; flex-direction: column; }.heading-actions { width: 100%; justify-content: space-between; } }
</style>
