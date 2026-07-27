<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef } from 'vue'
import {
  cancelShadowBatch,
  createShadowBatch,
  createShadowReview,
  getShadowBatch,
  getShadowBatches,
  getShadowItems,
  getShadowMetrics,
  getShadowReviews,
  getShadowStatus,
} from '../agent-shadow/api'
import type {
  ShadowBatch,
  ShadowFeatureStatus,
  ShadowItem,
  ShadowMetrics,
  ShadowReview,
  ShadowReviewLabel,
  ShadowSelectionMode,
} from '../agent-shadow/types'
import { AGENT_NAMES, FINAL_DECISION_NAMES, localIsoDate } from '../agent-team/presentation'
import type { FinalDecisionCode } from '../agent-team/types'

const POLL_MS = 3000
const terminalStatuses = new Set(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'])

const status = shallowRef<ShadowFeatureStatus | null>(null)
const batches = shallowRef<ShadowBatch[]>([])
const selectedBatch = shallowRef<ShadowBatch | null>(null)
const items = shallowRef<ShadowItem[]>([])
const selectedItem = shallowRef<ShadowItem | null>(null)
const reviews = shallowRef<ShadowReview[]>([])
const metrics = shallowRef<ShadowMetrics | null>(null)
const loading = ref(false)
const creating = ref(false)
const reviewSaving = ref(false)
const errorMessage = ref('')

const form = reactive({
  tradeDate: localIsoDate(),
  selectionMode: 'EXPLICIT' as ShadowSelectionMode,
  symbols: '',
  maxSymbols: 10,
  createdBy: 'local-researcher',
})

const reviewForm = reactive({
  label: 'EXPECTED' as ShadowReviewLabel,
  note: '',
  reviewer: 'local-researcher',
  supersedesReviewId: null as number | null,
})

let timer: number | null = null

const activeBatch = computed(() =>
  selectedBatch.value != null && !terminalStatuses.has(selectedBatch.value.status),
)

const progress = computed(() => {
  const batch = selectedBatch.value
  if (!batch || batch.selectedCount === 0) return 0
  return Math.round(batch.terminalCount * 100 / batch.selectedCount)
})

function safeError(error: unknown, fallback: string): string {
  if (!(error instanceof Error) || !error.message.trim()) return fallback
  return error.message.length <= 240 ? error.message : fallback
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '暂无'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString()
}

function distribution(values: Record<string, number> | undefined): string {
  if (!values) return '暂无'
  const entries = Object.entries(values).filter(([, value]) => value > 0)
  return entries.length ? entries.map(([key, value]) => `${key}: ${value}`).join(' · ') : '暂无记录'
}

function runSummary(item: ShadowItem): string {
  const runs = item.runSnapshot?.runs ?? []
  return runs.map(run => `${AGENT_NAMES[run.agentCode]} ${run.status}`).join(' · ') || '暂无六 run 快照'
}

function decisionName(value: FinalDecisionCode | null): string {
  return value == null ? '暂无' : `${FINAL_DECISION_NAMES[value]} / ${value}`
}

function parseSymbols(): string[] {
  return form.symbols
    .split(/[\s,，;；]+/)
    .map(value => value.trim())
    .filter(Boolean)
}

async function loadOverview(): Promise<void> {
  const [loadedStatus, loadedBatches, loadedMetrics] = await Promise.all([
    getShadowStatus(),
    getShadowBatches(),
    getShadowMetrics(),
  ])
  status.value = loadedStatus
  batches.value = loadedBatches
  metrics.value = loadedMetrics
}

async function selectBatch(batch: ShadowBatch): Promise<void> {
  selectedBatch.value = batch
  items.value = await getShadowItems(batch.id)
  const selectedId = selectedItem.value?.id
  if (selectedId != null) {
    selectedItem.value = items.value.find(item => item.id === selectedId) ?? null
  }
}

async function selectItem(item: ShadowItem): Promise<void> {
  selectedItem.value = item
  reviews.value = await getShadowReviews(item.id)
  reviewForm.supersedesReviewId = null
}

async function refresh(): Promise<void> {
  if (loading.value) return
  loading.value = true
  try {
    errorMessage.value = ''
    await loadOverview()
    if (selectedBatch.value) {
      const refreshed = await getShadowBatch(selectedBatch.value.id)
      await selectBatch(refreshed)
    }
  } catch (error) {
    errorMessage.value = safeError(error, '影子运行状态加载失败')
  } finally {
    loading.value = false
  }
}

async function startBatch(): Promise<void> {
  if (!status.value?.enabled) {
    errorMessage.value = '影子运行功能当前关闭；请在本地配置中显式启用后再运行。'
    return
  }
  const symbols = parseSymbols()
  if (form.selectionMode === 'EXPLICIT' && (symbols.length < 1 || symbols.length > 20)) {
    errorMessage.value = 'EXPLICIT 模式需要 1 至 20 个六位股票代码。'
    return
  }
  if (symbols.some(symbol => !/^\d{6}$/.test(symbol))) {
    errorMessage.value = '每个股票代码都必须是六位数字。'
    return
  }
  creating.value = true
  try {
    errorMessage.value = ''
    const batch = await createShadowBatch({
      tradeDate: form.tradeDate,
      selectionMode: form.selectionMode,
      explicitSymbols: form.selectionMode === 'EXPLICIT' ? symbols : [],
      maxSymbols: form.maxSymbols,
      createdBy: form.createdBy.trim(),
    })
    await loadOverview()
    await selectBatch(batch)
  } catch (error) {
    errorMessage.value = safeError(error, '影子批次创建失败')
  } finally {
    creating.value = false
  }
}

async function cancelBatch(): Promise<void> {
  if (!selectedBatch.value || !activeBatch.value) return
  try {
    await cancelShadowBatch(selectedBatch.value.id)
    await refresh()
  } catch (error) {
    errorMessage.value = safeError(error, '取消请求失败')
  }
}

async function addReview(): Promise<void> {
  if (!selectedItem.value || !reviewForm.note.trim() || !reviewForm.reviewer.trim()) {
    errorMessage.value = '复核标签、说明和复核人不能为空。'
    return
  }
  reviewSaving.value = true
  try {
    await createShadowReview(selectedItem.value.id, {
      label: reviewForm.label,
      note: reviewForm.note.trim(),
      reviewer: reviewForm.reviewer.trim(),
      supersedesReviewId: reviewForm.supersedesReviewId,
    })
    reviews.value = await getShadowReviews(selectedItem.value.id)
    reviewForm.note = ''
    reviewForm.supersedesReviewId = null
    metrics.value = await getShadowMetrics()
  } catch (error) {
    errorMessage.value = safeError(error, '保存人工复核失败')
  } finally {
    reviewSaving.value = false
  }
}

onMounted(async () => {
  await refresh()
  timer = window.setInterval(() => void refresh(), POLL_MS)
})

onBeforeUnmount(() => {
  if (timer != null) window.clearInterval(timer)
})
</script>

<template>
  <div class="shadow-workbench" v-loading="loading">
    <section class="panel hero">
      <div>
        <p class="eyebrow">CONTROLLED SHADOW OBSERVATION</p>
        <h1>受控影子运行与就绪度观测</h1>
        <p class="lead">真实运行 2I 确定性总控，记录决定、不足、失败、漂移与人工复核。</p>
      </div>
      <div class="status-stack">
        <el-tag :type="status?.enabled ? 'success' : 'info'">
          影子功能 {{ status?.enabled ? '已启用' : '默认关闭' }}
        </el-tag>
        <el-tag :type="status?.schedulerEnabled ? 'warning' : 'info'">
          Scheduler {{ status?.schedulerEnabled ? '已启用' : '关闭' }}
        </el-tag>
      </div>
      <el-alert
        class="safety-alert"
        type="warning"
        show-icon
        :closable="false"
        title="影子运行不会触发交易或修改持仓；不会自动抓取公告、刷新行情或启动全市场扫描。"
      />
    </section>

    <el-alert
      v-if="errorMessage"
      type="error"
      show-icon
      closable
      :title="errorMessage"
      @close="errorMessage = ''"
    />

    <section class="panel">
      <div class="section-heading">
        <div><p class="eyebrow">MANUAL CONTROL</p><h2>启动受控批次</h2></div>
        <code>{{ status?.ruleVersion }}</code>
      </div>
      <el-form class="create-grid" label-position="top" @submit.prevent="startBatch">
        <el-form-item label="交易日期">
          <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="选择模式">
          <el-select v-model="form.selectionMode">
            <el-option label="显式代码" value="EXPLICIT" />
            <el-option label="持仓 + 最近扫描" value="AUTO" />
          </el-select>
        </el-form-item>
        <el-form-item label="最大股票数">
          <el-input-number v-model="form.maxSymbols" :min="1" :max="20" />
        </el-form-item>
        <el-form-item v-if="form.selectionMode === 'EXPLICIT'" label="股票代码（逗号或空格分隔）" class="symbols">
          <el-input v-model="form.symbols" placeholder="例如：000001, 600000" />
        </el-form-item>
        <el-form-item label="创建人">
          <el-input v-model="form.createdBy" maxlength="128" />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="creating"
          :disabled="creating || !status?.enabled"
        >
          启动影子批次
        </el-button>
      </el-form>
      <p class="limit-note">
        安全窗口 {{ status?.safeWindowStart }}–{{ status?.safeWindowEnd }} {{ status?.zone }}；
        自动调度必须同时打开两个开关，本页面不会自动打开 Scheduler。
      </p>
    </section>

    <section class="metrics-grid" v-if="metrics">
      <article class="metric"><span>批次 / Item</span><strong>{{ metrics.batchCount }} / {{ metrics.itemCount }}</strong></article>
      <article class="metric"><span>缓存命中</span><strong>{{ metrics.cacheHitCount }} / {{ (metrics.cacheHitRate * 100).toFixed(1) }}%</strong></article>
      <article class="metric"><span>P50 / P95</span><strong>{{ metrics.p50DurationMs ?? '—' }} / {{ metrics.p95DurationMs ?? '—' }} ms</strong></article>
      <article class="metric"><span>未复核</span><strong>{{ metrics.unreviewedItemCount }}</strong></article>
      <article class="metric wide"><span>结果分布</span><strong>{{ distribution(metrics.finalDecisionDistribution) }}</strong></article>
      <article class="metric wide"><span>主要不足原因</span><strong>{{ distribution(metrics.primaryReasonCodeDistribution) }}</strong></article>
    </section>

    <section class="panel">
      <div class="section-heading">
        <div><p class="eyebrow">BATCHES</p><h2>影子批次</h2></div>
        <el-button size="small" @click="refresh">刷新</el-button>
      </div>
      <el-table :data="batches" @row-click="selectBatch">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="tradeDate" label="日期" width="110" />
        <el-table-column prop="selectionMode" label="选择" width="100" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="进度">
          <template #default="{ row }">{{ row.terminalCount }} / {{ row.selectedCount }}</template>
        </el-table-column>
        <el-table-column label="确定/不足/失败">
          <template #default="{ row }">{{ row.determinedCount }} / {{ row.insufficientCount }} / {{ row.failedCount }}</template>
        </el-table-column>
        <el-table-column prop="createdBy" label="创建人" />
      </el-table>
    </section>

    <section class="panel" v-if="selectedBatch">
      <div class="section-heading">
        <div>
          <p class="eyebrow">BATCH #{{ selectedBatch.id }}</p>
          <h2>{{ selectedBatch.status }} · {{ selectedBatch.tradeDate }}</h2>
        </div>
        <el-button v-if="activeBatch" type="warning" @click="cancelBatch">安全取消</el-button>
      </div>
      <el-progress :percentage="progress" />
      <div class="facts">
        <div><span>Selection Hash</span><code>{{ selectedBatch.selectionHash }}</code></div>
        <div><span>缓存 / Veto / DQ阻断</span><strong>{{ selectedBatch.cacheHitCount }} / {{ selectedBatch.vetoCount }} / {{ selectedBatch.dataQualityBlockedCount }}</strong></div>
        <div><span>开始 / 完成</span><strong>{{ formatTime(selectedBatch.startedAt) }} / {{ formatTime(selectedBatch.finishedAt) }}</strong></div>
      </div>
      <el-alert v-if="selectedBatch.errorMessage" type="warning" :closable="false" :title="selectedBatch.errorMessage" />

      <el-table :data="items" class="items-table" @row-click="selectItem">
        <el-table-column prop="selectionOrder" label="#" width="55" />
        <el-table-column prop="symbol" label="股票" width="90" />
        <el-table-column prop="selectionSource" label="来源" width="170" />
        <el-table-column prop="outcomeClass" label="结果类" width="120" />
        <el-table-column label="总控结果" min-width="190">
          <template #default="{ row }">
            {{ decisionName(row.finalDecision) }}
          </template>
        </el-table-column>
        <el-table-column label="Score / Confidence" width="155">
          <template #default="{ row }">{{ row.score ?? '—' }} / {{ row.confidence ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="primaryReasonCode" label="主要原因" min-width="240" />
        <el-table-column label="缓存" width="70">
          <template #default="{ row }">{{ row.cacheHit ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="漂移" width="110">
          <template #default="{ row }">
            {{ row.previousItemId == null ? '首次' : row.contextChanged || row.decisionChanged ? '有变化' : '稳定' }}
          </template>
        </el-table-column>
        <el-table-column label="Task" width="95">
          <template #default="{ row }">
            <router-link v-if="row.agentTaskId" :to="`/agent-team?taskId=${row.agentTaskId}`">#{{ row.agentTaskId }}</router-link>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel" v-if="selectedItem">
      <div class="section-heading">
        <div><p class="eyebrow">ITEM #{{ selectedItem.id }}</p><h2>{{ selectedItem.symbol }} 运行与复核</h2></div>
        <el-tag>{{ selectedItem.outcomeClass ?? 'RUNNING' }}</el-tag>
      </div>
      <p class="run-summary">{{ runSummary(selectedItem) }}</p>
      <div class="facts">
        <div><span>Reason Codes</span><code>{{ (selectedItem.reasonCodes ?? []).join(', ') || '暂无' }}</code></div>
        <div><span>Context / Decision 漂移</span><strong>{{ selectedItem.contextChanged ?? '未知' }} / {{ selectedItem.decisionChanged ?? '未知' }}</strong></div>
        <div><span>耗时</span><strong>{{ selectedItem.durationMs ?? '—' }} ms</strong></div>
      </div>

      <div class="review-grid">
        <div>
          <h3>人工复核历史</h3>
          <div v-if="!reviews.length" class="empty">暂无复核</div>
          <article v-for="review in reviews" :key="review.id" class="review-card">
            <strong>{{ review.label }}</strong>
            <span>{{ review.reviewer }} · {{ formatTime(review.createdAt) }}</span>
            <p>{{ review.note }}</p>
            <small v-if="review.supersedesReviewId">更正复核 #{{ review.supersedesReviewId }}</small>
          </article>
        </div>
        <el-form label-position="top" @submit.prevent="addReview">
          <h3>新增复核（append-only）</h3>
          <el-form-item label="标签">
            <el-select v-model="reviewForm.label">
              <el-option v-for="label in ['EXPECTED','UNEXPECTED','DATA_ISSUE','RULE_ISSUE','FALSE_POSITIVE','FALSE_NEGATIVE','NEEDS_FOLLOW_UP']" :key="label" :label="label" :value="label" />
            </el-select>
          </el-form-item>
          <el-form-item label="说明"><el-input v-model="reviewForm.note" type="textarea" :rows="4" maxlength="4000" /></el-form-item>
          <el-form-item label="复核人"><el-input v-model="reviewForm.reviewer" maxlength="128" /></el-form-item>
          <el-form-item label="更正既有复核（可选）">
            <el-select v-model="reviewForm.supersedesReviewId" clearable>
              <el-option v-for="review in reviews" :key="review.id" :label="`#${review.id} ${review.label}`" :value="review.id" />
            </el-select>
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="reviewSaving" :disabled="!selectedItem.outcomeClass">保存复核</el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.shadow-workbench { display: grid; gap: 18px; min-width: 0; }
.panel, .metric { border: 1px solid var(--border); background: linear-gradient(145deg, rgba(17,30,52,.96), rgba(11,18,32,.98)); border-radius: 10px; box-shadow: 0 12px 30px rgba(0,0,0,.18); }
.panel { padding: 22px; }
.hero { display: grid; grid-template-columns: 1fr auto; gap: 16px; }
.hero .safety-alert { grid-column: 1 / -1; }
h1, h2, h3, p { margin-top: 0; }
h1, h2 { margin-bottom: 0; }
.lead { margin: 10px 0 0; color: var(--muted); }
.eyebrow { margin-bottom: 5px; color: var(--accent); font-size: 11px; font-weight: 700; letter-spacing: .16em; }
.status-stack { display: flex; align-items: flex-start; gap: 8px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 17px; }
.create-grid { display: grid; grid-template-columns: repeat(3, minmax(150px, 1fr)); align-items: end; gap: 0 16px; }
.create-grid :deep(.el-date-editor), .create-grid :deep(.el-select), .create-grid :deep(.el-input-number) { width: 100%; }
.symbols { grid-column: span 2; }
.limit-note { margin: 8px 0 0; color: var(--muted); font-size: 12px; }
.metrics-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.metric { min-width: 0; padding: 16px; }
.metric.wide { grid-column: span 2; }
.metric span, .facts span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 12px; }
.metric strong { overflow-wrap: anywhere; color: var(--accent); font-size: 18px; }
.facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin: 16px 0; }
.facts code, .facts strong { overflow-wrap: anywhere; }
.items-table { margin-top: 18px; }
.run-summary { line-height: 1.7; }
.review-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 22px; }
.review-card { margin-bottom: 10px; padding: 13px; border: 1px solid var(--border); border-radius: 8px; background: rgba(8,14,25,.55); }
.review-card span { display: block; margin: 4px 0; color: var(--muted); font-size: 12px; }
.review-card p { margin: 8px 0; line-height: 1.6; }
.review-card small, .empty { color: var(--muted); }
code { color: var(--muted); font-size: 11px; }
@media (max-width: 1100px) {
  .metrics-grid, .create-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .hero, .review-grid, .metrics-grid, .create-grid, .facts { grid-template-columns: 1fr; }
  .metric.wide, .symbols { grid-column: auto; }
  .section-heading { align-items: flex-start; flex-direction: column; }
}
</style>
