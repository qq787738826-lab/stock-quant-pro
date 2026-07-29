<script setup lang="ts">
import { computed, ref } from 'vue'
import { displayMetric, displayValue } from '../../research-preview/presentation'
import type {
  PreviewCandidate,
  PreviewMode,
  PreviewQualification,
  ScanTaskSnapshot,
} from '../../research-preview/types'

const props = defineProps<{
  mode: PreviewMode
  qualification: PreviewQualification
  tasks: ScanTaskSnapshot[]
  selectedTaskId: number | null
  selectedTask: ScanTaskSnapshot | null
  candidates: PreviewCandidate[]
  loading: boolean
}>()

const emit = defineEmits<{
  selectScan: [taskId: number]
  selectCandidate: [candidate: PreviewCandidate]
}>()

const keyword = ref('')
const agentFilter = ref<'ALL' | 'WITH_AGENT' | 'WITHOUT_AGENT'>('ALL')

const filteredCandidates = computed(() => {
  const query = keyword.value.trim().toUpperCase()
  return props.candidates.filter((candidate) => {
    if (
      query
      && !candidate.symbol.toUpperCase().includes(query)
      && !candidate.name.toUpperCase().includes(query)
    ) return false
    if (agentFilter.value === 'WITH_AGENT' && !candidate.hasAgentResult) return false
    if (agentFilter.value === 'WITHOUT_AGENT' && candidate.hasAgentResult) return false
    return true
  })
})

function changeTask(value: string | number | boolean | undefined): void {
  const taskId = Number(value)
  if (Number.isSafeInteger(taskId)) emit('selectScan', taskId)
}
</script>

<template>
  <section class="preview-panel candidate-panel" :aria-busy="loading">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">CANDIDATE SNAPSHOT</p>
        <h2>候选股票池</h2>
      </div>
      <span class="qualification">{{ qualification }}</span>
    </div>

    <div class="scan-facts">
      <div><span>扫描任务</span><strong>{{ selectedTask?.id ?? '暂无' }}</strong></div>
      <div><span>交易日期</span><strong>{{ selectedTask?.trade_date ?? '暂无' }}</strong></div>
      <div><span>任务状态</span><strong>{{ selectedTask?.status ?? '暂无' }}</strong></div>
      <div><span>是否正式</span><strong>{{ selectedTask?.official == null ? '暂无' : selectedTask.official ? '是' : '否' }}</strong></div>
    </div>

    <div class="candidate-tools">
      <el-select
        v-if="mode === 'EXISTING_RESEARCH_SNAPSHOT'"
        :model-value="selectedTaskId ?? undefined"
        placeholder="切换已有扫描任务"
        filterable
        @change="changeTask"
      >
        <el-option
          v-for="task in tasks"
          :key="task.id"
          :label="`#${task.id ?? '暂无'} · ${task.trade_date ?? '日期暂无'} · ${task.status ?? '状态暂无'}`"
          :value="task.id"
        />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="前端搜索股票代码 / 名称" />
      <el-select v-model="agentFilter">
        <el-option label="全部Agent关联状态" value="ALL" />
        <el-option label="已有Agent结果" value="WITH_AGENT" />
        <el-option label="暂无Agent结果" value="WITHOUT_AGENT" />
      </el-select>
      <span class="result-count">显示 {{ filteredCandidates.length }} / {{ candidates.length }}</span>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>排名</th>
            <th>代码 / 名称</th>
            <th>已存评分</th>
            <th>候选资格</th>
            <th>风险 / 信号</th>
            <th>已有metrics安全字段</th>
            <th>Agent历史</th>
            <th>数据标签</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="candidate in filteredCandidates"
            :key="candidate.symbol"
            tabindex="0"
            @click="emit('selectCandidate', candidate)"
            @keydown.enter="emit('selectCandidate', candidate)"
          >
            <td>{{ displayValue(candidate.rank) }}</td>
            <td><b>{{ candidate.symbol }}</b><span>{{ candidate.name }}</span></td>
            <td>{{ displayValue(candidate.score) }}</td>
            <td>
              <span v-if="candidate.eligible === true" class="state ok">符合</span>
              <span v-else-if="candidate.eligible === false" class="state warn">不符合</span>
              <span v-else>暂无</span>
            </td>
            <td>{{ displayValue(candidate.riskLevel) }} / {{ displayValue(candidate.signalLevel) }}</td>
            <td class="metrics-cell">
              <span>RSI：{{ displayMetric(candidate.metrics, 'rsi14') }}</span>
              <span>ATR：{{ displayMetric(candidate.metrics, 'atr14Pct') }}</span>
              <span>量比：{{ displayMetric(candidate.metrics, 'volumeRatio20') }}</span>
              <span>突破：{{ displayMetric(candidate.metrics, 'breakout20') }}</span>
            </td>
            <td><span :class="['state', candidate.hasAgentResult ? 'ok' : 'muted-state']">{{ candidate.hasAgentResult ? '已有' : '暂无' }}</span></td>
            <td><code>{{ candidate.qualification }}</code><small v-if="candidate.synthetic">SYNTHETIC</small></td>
          </tr>
          <tr v-if="!loading && !filteredCandidates.length">
            <td colspan="8" class="empty-cell">PREVIEW_SCAN_RESULTS_EMPTY · 当前筛选下暂无已有候选结果</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.preview-panel { padding: 20px; border: 1px solid #223a57; border-radius: 12px; background: #0e1a2b; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h2 { margin: 0; font-size: 19px; }.eyebrow { margin: 0 0 5px; color: #5eaaff; font-size: 10px; font-weight: 800; letter-spacing: .15em; }
.qualification { color: #78d8b1; font: 11px ui-monospace, monospace; }
.scan-facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 15px 0; }
.scan-facts div { padding: 9px 11px; border: 1px solid #1f3249; border-radius: 7px; background: #0a1524; }
.scan-facts span { display: block; color: #70839d; font-size: 10px; }.scan-facts strong { font-size: 12px; }
.candidate-tools { display: grid; grid-template-columns: 1.2fr 1fr 1fr auto; gap: 10px; align-items: center; margin-bottom: 12px; }
.result-count { color: #778ba5; font-size: 11px; }.table-wrap { overflow: auto; border: 1px solid #1f3249; border-radius: 8px; }
table { width: 100%; min-width: 1120px; border-collapse: collapse; font-size: 12px; }
th { padding: 10px; color: #7488a2; text-align: left; background: #0a1524; font-weight: 600; }
td { padding: 11px 10px; border-top: 1px solid #1a2b40; vertical-align: top; }
tbody tr { cursor: pointer; }tbody tr:hover, tbody tr:focus { outline: none; background: #142943; }
td b { display: block; color: #e9f2ff; }td > span { display: block; margin-top: 3px; color: #8ca0ba; }
.metrics-cell { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 8px; color: #9eb0c6; }
.state { display: inline-block; margin: 0; padding: 2px 6px; border-radius: 99px; }.state.ok { color: #55d5a0; background: #123b30; }.state.warn { color: #ffbd66; background: #46351c; }.state.muted-state { color: #8796aa; background: #253247; }
code { display: block; color: #70c8ff; font-size: 10px; }small { display: block; margin-top: 4px; color: #ffca73; }
.empty-cell { padding: 30px; color: #778ba5; text-align: center; cursor: default; }
@media (max-width: 1250px) { .candidate-tools { grid-template-columns: 1fr 1fr; }.scan-facts { grid-template-columns: repeat(2, 1fr); } }
</style>
