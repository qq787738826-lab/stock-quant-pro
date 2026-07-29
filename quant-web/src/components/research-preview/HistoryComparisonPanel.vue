<script setup lang="ts">
import { computed, reactive } from 'vue'
import { AGENT_NAMES } from '../../agent-team/presentation'
import {
  compareTaskBundles,
  displayTime,
} from '../../research-preview/presentation'
import type { AgentTask, PageResult } from '../../agent-team/types'
import type {
  PreviewMode,
  PreviewTaskBundle,
} from '../../research-preview/types'

const props = defineProps<{
  mode: PreviewMode
  history: PageResult<AgentTask>
  activeTaskId: number | null
  left: PreviewTaskBundle | null
  right: PreviewTaskBundle | null
  loading: boolean
}>()

const emit = defineEmits<{
  page: [page: number]
  loadTask: [taskId: number]
  compare: [side: 'left' | 'right', taskId: number]
}>()

const filters = reactive({
  symbol: '',
  taskId: '',
  tradeDate: '',
  ruleVersion: '',
  status: '',
})

const filtered = computed(() => props.history.content.filter((task) => {
  const symbol = filters.symbol.trim().toUpperCase()
  if (symbol && !task.symbol.toUpperCase().includes(symbol)) return false
  const taskId = filters.taskId.trim()
  if (taskId && !String(task.id).includes(taskId)) return false
  if (filters.tradeDate && task.tradeDate !== filters.tradeDate) return false
  const rule = filters.ruleVersion.trim().toLowerCase()
  if (rule && !task.ruleVersion.toLowerCase().includes(rule)) return false
  if (filters.status && task.status !== filters.status) return false
  return true
}))

const comparison = computed(() => compareTaskBundles(props.left, props.right))

function selectComparison(side: 'left' | 'right', value: string | number | boolean | undefined): void {
  const taskId = Number(value)
  if (Number.isSafeInteger(taskId)) emit('compare', side, taskId)
}
</script>

<template>
  <section class="history-panel">
    <div class="panel-heading">
      <div><p class="eyebrow">READ-ONLY HISTORY</p><h2>历史任务</h2></div>
      <span>
        {{ mode === 'TEST_DEMO_EXPLICIT' ? '演示数据' : '本地研究快照' }}
        · 总数 {{ history.total }} · 当前页 {{ history.page + 1 }} · 每页 {{ history.size }}
      </span>
    </div>

    <div class="filters">
      <el-input v-model="filters.symbol" clearable placeholder="股票筛选" />
      <el-input v-model="filters.taskId" clearable placeholder="taskId筛选" />
      <el-input v-model="filters.tradeDate" clearable placeholder="交易日期 YYYY-MM-DD" />
      <el-input v-model="filters.ruleVersion" clearable placeholder="ruleVersion筛选" />
      <el-select v-model="filters.status" clearable placeholder="状态筛选">
        <el-option
          v-for="status in ['QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED']"
          :key="status"
          :label="status"
          :value="status"
        />
      </el-select>
    </div>

    <div class="history-table">
      <table>
        <thead>
          <tr><th>taskId</th><th>股票</th><th>日期</th><th>ruleVersion</th><th>状态</th><th>创建时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="task in filtered" :key="task.id" :class="{ active: task.id === activeTaskId }">
            <td>{{ task.id }}</td>
            <td>{{ task.symbol }} <em v-if="task.id === activeTaskId">当前任务</em></td>
            <td>{{ task.tradeDate }}</td>
            <td><code>{{ task.ruleVersion }}</code></td>
            <td>{{ task.status }}</td>
            <td>{{ displayTime(task.createdAt) }}</td>
            <td>
              <el-button size="small" text type="primary" @click="emit('loadTask', task.id)">查看</el-button>
              <el-button size="small" text @click="emit('compare', 'left', task.id)">设为A</el-button>
              <el-button size="small" text @click="emit('compare', 'right', task.id)">设为B</el-button>
            </td>
          </tr>
          <tr v-if="!filtered.length"><td colspan="7" class="empty">当前筛选下暂无已加载历史任务</td></tr>
        </tbody>
      </table>
    </div>

    <el-pagination
      v-if="mode === 'EXISTING_RESEARCH_SNAPSHOT' && history.total > history.size"
      background
      layout="prev, pager, next, total"
      :page-size="history.size"
      :total="history.total"
      :current-page="history.page + 1"
      :disabled="loading"
      @current-change="(page: number) => emit('page', page - 1)"
    />

    <details class="comparison-section">
      <summary>
        <span><b>展开任务对比</b><small>只读列出差异，不评价任务优劣</small></span>
        <i>按需展开</i>
      </summary>
      <div class="comparison-body">
        <div class="comparison-selects">
          <div>
            <label>对比任务A</label>
            <el-select
              :model-value="left?.task.id"
              filterable
              placeholder="选择已有任务"
              @change="selectComparison('left', $event)"
            >
              <el-option
                v-for="task in history.content"
                :key="`left-${task.id}`"
                :label="`#${task.id} ${task.symbol} ${task.tradeDate}`"
                :value="task.id"
              />
            </el-select>
          </div>
          <div>
            <label>对比任务B</label>
            <el-select
              :model-value="right?.task.id"
              filterable
              placeholder="选择已有任务"
              @change="selectComparison('right', $event)"
            >
              <el-option
                v-for="task in history.content"
                :key="`right-${task.id}`"
                :label="`#${task.id} ${task.symbol} ${task.tradeDate}`"
                :value="task.id"
              />
            </el-select>
          </div>
        </div>

        <div v-if="!comparison" class="empty">请选择两个当前模式下的已有任务进行只读对比。</div>
        <template v-else>
          <div v-if="comparison.warnings.length" class="comparison-warnings">
            <p v-for="warning in comparison.warnings" :key="warning">{{ warning }}</p>
          </div>
          <p class="boundary">对比只列出差异，不计算哪个任务“更好”，也不生成投资结论。</p>
          <div class="comparison-table">
            <table>
              <thead><tr><th>维度</th><th>任务A</th><th>任务B</th><th>是否不同</th></tr></thead>
              <tbody>
                <tr v-for="row in comparison.rows" :key="row.key">
                  <td>{{ row.label }}</td><td>{{ row.left }}</td><td>{{ row.right }}</td>
                  <td :class="{ differs: row.differs }">{{ row.differs ? '不同' : '一致' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="agent-comparison">
            <article v-for="row in comparison.agentRows" :key="row.agentCode">
              <b>{{ AGENT_NAMES[row.agentCode] }}</b>
              <code>{{ row.agentCode }}</code>
              <span>A：{{ row.statusLeft }} / 分数{{ row.scoreLeft }} / 置信度{{ row.confidenceLeft }}</span>
              <span>B：{{ row.statusRight }} / 分数{{ row.scoreRight }} / 置信度{{ row.confidenceRight }}</span>
            </article>
          </div>
        </template>
      </div>
    </details>
  </section>
</template>

<style scoped>
.history-panel { min-width: 0; padding: 18px; border: 1px solid #263f5b; border-radius: 12px; background: #0e1b2c; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h2 { margin: 0; font-size: 19px; }
.panel-heading > span { color: #9cafc4; font-size: 12px; }
.eyebrow { margin: 0 0 5px; color: #73b7f2; font-size: 11px; font-weight: 800; letter-spacing: .13em; }
.filters { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; margin: 14px 0; }
.history-table, .comparison-table { overflow: auto; border: 1px solid #1d3047; border-radius: 8px; }
table { width: 100%; min-width: 850px; border-collapse: collapse; font-size: 13px; }
th { padding: 9px; color: #9aadc3; text-align: left; background: #091422; }
td { padding: 9px; border-top: 1px solid #192a3f; }
tr.active { background: #183754; box-shadow: inset 4px 0 #58a9e8; }
td code { color: #85bff0; font-size: 11px; }
td em { display: inline-block; margin-left: 5px; padding: 2px 5px; border-radius: 999px; background: #28516f; color: #a8d9fa; font-size: 11px; font-style: normal; }
.empty { padding: 24px; color: #94a7bd; font-size: 13px; text-align: center; }
:deep(.el-pagination) { margin-top: 12px; justify-content: flex-end; }
.comparison-section { margin-top: 18px; border: 1px solid #253d57; border-radius: 9px; background: #091523; }
.comparison-section > summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px;
  cursor: pointer;
  list-style: none;
}
.comparison-section > summary::-webkit-details-marker { display: none; }
.comparison-section > summary span { display: grid; gap: 4px; }
.comparison-section > summary b { font-size: 14px; }
.comparison-section > summary small { color: #95a8bd; font-size: 12px; }
.comparison-section > summary i { color: #82bff0; font-size: 12px; font-style: normal; }
.comparison-body { padding: 0 13px 13px; border-top: 1px solid #21364e; }
.comparison-selects { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 13px; }
.comparison-selects div { display: grid; gap: 5px; }
.comparison-selects label { color: #9aacc0; font-size: 12px; }
.comparison-warnings { margin-top: 12px; padding: 9px 11px; border: 1px solid #785526; border-radius: 7px; background: #362712; }
.comparison-warnings p { margin: 3px 0; color: #ecc27c; font-size: 12px; }
.boundary { color: #98aabd; font-size: 12px; }
.differs { color: #efbd72; }
.agent-comparison { display: grid; grid-template-columns: repeat(3, 1fr); gap: 7px; margin-top: 10px; }
.agent-comparison article { display: grid; gap: 4px; padding: 8px; border: 1px solid #1d3148; border-radius: 6px; background: #091422; }
.agent-comparison b { font-size: 13px; }
.agent-comparison code { color: #7eb8e6; font-size: 11px; }
.agent-comparison span { color: #a3b3c6; font-size: 12px; }
@media (max-width: 1300px) {
  .filters { grid-template-columns: repeat(3, 1fr); }
  .agent-comparison { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 850px) {
  .filters, .comparison-selects { grid-template-columns: 1fr 1fr; }
}
</style>
