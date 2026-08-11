<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAgentResearchReport, getAgentResearchReports } from '../agent-research/api'
import type { ReportSummary, ResearchReport } from '../agent-research/types'

const route = useRoute()
const router = useRouter()
const history = shallowRef<ReportSummary[]>([])
const report = shallowRef<ResearchReport | null>(null)
const loading = ref(false)
const error = ref('')

const roleRuns = computed(() => report.value?.agentRuns ?? [])
const experiments = computed(() => report.value?.strategyExperiments.experiments ?? [])

function percent(value: number | null | undefined): string {
  return value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`
}

function number(value: number | null | undefined, digits = 4): string {
  return value == null ? '—' : Number(value).toFixed(digits)
}

function shortHash(value: string | null | undefined): string {
  return value ? `${value.slice(0, 12)}…` : '—'
}

async function load(taskId: string): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    report.value = await getAgentResearchReport(taskId)
    await router.replace({ path: '/agent-research', query: { taskId } })
  } catch (cause) {
    report.value = null
    error.value = cause instanceof Error ? cause.message : '研究报告加载失败'
  } finally {
    loading.value = false
  }
}

function selectReport(row: ReportSummary): void {
  void load(row.taskId)
}

onMounted(async () => {
  loading.value = true
  try {
    history.value = await getAgentResearchReports()
    const requested = typeof route.query.taskId === 'string' ? route.query.taskId : ''
    const taskId = requested || history.value[0]?.taskId
    if (taskId) await load(taskId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '研究报告列表加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="research-workbench" v-loading="loading">
    <header class="page-head">
      <div>
        <p class="eyebrow">AGENT_RESEARCH_TEAM_V1 · READ ONLY</p>
        <h1>智能体研究报告</h1>
        <p>数据、回测和风险指标均来自 M1/M2 确定性工具；本页面不能启动交易或 Shadow。</p>
      </div>
      <el-tag type="success" effect="dark">RESEARCH OUTPUT</el-tag>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-empty v-if="!loading && !error && history.length === 0" description="暂无 M3 脱敏研究报告" />

    <section v-if="history.length" class="panel">
      <h2>研究任务</h2>
      <el-table :data="history" size="small" highlight-current-row @row-click="selectReport">
        <el-table-column prop="taskId" label="任务" min-width="250" />
        <el-table-column prop="status" label="状态" width="180" />
        <el-table-column prop="preferredStrategy" label="研究偏好" min-width="210" />
        <el-table-column label="置信度" width="100"><template #default="scope">{{ percent(scope.row.confidence) }}</template></el-table-column>
        <el-table-column prop="completedAt" label="完成时间" min-width="190" />
      </el-table>
    </section>

    <template v-if="report">
      <section class="summary-grid">
        <article class="metric"><span>状态</span><strong>{{ report.status }}</strong></article>
        <article class="metric"><span>证券 / 开市日</span><strong>{{ report.dataset.securityCount }} / {{ report.dataset.openSessionCount }}</strong></article>
        <article class="metric"><span>工具 / 模型调用</span><strong>{{ report.toolCallCount }} / {{ report.modelCallCount }}</strong></article>
        <article class="metric"><span>研究置信度</span><strong>{{ percent(report.finalDecision.confidence) }}</strong></article>
      </section>

      <section class="panel">
        <h2>任务与数据证据</h2>
        <p>{{ report.task.objective }}</p>
        <div class="facts">
          <span>窗口 {{ report.task.rangeStart }} → {{ report.task.rangeEnd }}</span>
          <span>Dataset {{ shortHash(report.dataset.datasetFingerprint) }}</span>
          <span>Typed fact {{ report.dataset.typedFactReadback ? 'PASS' : 'FAIL' }}</span>
          <span>SYSTEM_KNOWLEDGE {{ report.dataset.systemKnowledgeReadback ? 'PASS' : 'FAIL' }}</span>
          <span>No future data {{ report.dataset.noFutureDataLeakage ? 'PASS' : 'FAIL' }}</span>
          <span>Provider PIT {{ report.dataset.providerPitVerified ? 'VERIFIED' : 'NOT VERIFIED' }}</span>
        </div>
      </section>

      <section class="panel">
        <h2>策略实验与 M2 回测</h2>
        <el-table :data="experiments" size="small">
          <el-table-column prop="strategyCode" label="策略" min-width="250" />
          <el-table-column label="总收益" width="100"><template #default="scope">{{ percent(scope.row.totalReturn) }}</template></el-table-column>
          <el-table-column label="Sharpe" width="90"><template #default="scope">{{ number(scope.row.sharpeRatio, 3) }}</template></el-table-column>
          <el-table-column label="最大回撤" width="110"><template #default="scope">{{ percent(scope.row.maxDrawdown) }}</template></el-table-column>
          <el-table-column label="超额收益" width="110"><template #default="scope">{{ percent(scope.row.excessReturn) }}</template></el-table-column>
          <el-table-column prop="fillCount" label="成交" width="75" />
          <el-table-column label="OOS" width="80"><template #default="scope">{{ scope.row.outOfSampleEvaluated ? 'PASS' : 'N/A' }}</template></el-table-column>
          <el-table-column prop="walkForwardFolds" label="WF folds" width="90" />
          <el-table-column label="过拟合" width="80"><template #default="scope">{{ scope.row.overfittingFlag ? 'RISK' : 'NO' }}</template></el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <h2>7 Agent 协作轨迹</h2>
        <div class="agent-grid">
          <article v-for="run in roleRuns" :key="run.runId" class="agent-card">
            <div><strong>{{ run.agentRole }}</strong><el-tag size="small">{{ run.status }}</el-tag></div>
            <small>{{ run.phase }} · {{ run.promptVersion }} · {{ run.modelProvider }}/{{ run.model }}</small>
            <p v-for="finding in run.findings" :key="finding.findingId"><b>{{ finding.claimType }}</b> {{ finding.statement }}<em>{{ finding.evidenceIds.length }} evidence</em></p>
          </article>
        </div>
      </section>

      <section class="two-column">
        <article class="panel">
          <h2>Critic 质疑与修正</h2>
          <p><strong>Issues:</strong> {{ report.criticReview.issues.join(', ') || 'NONE' }}</p>
          <p><strong>返工:</strong> {{ report.criticReview.reworkRequested ? 'YES' : 'NO' }} · 修正 {{ report.criticReview.correctionApplied ? 'APPLIED' : 'N/A' }}</p>
          <p><strong>限制:</strong> {{ report.portfolio.limitations.join(', ') || 'NONE' }}</p>
        </article>
        <article class="panel decision">
          <h2>最终研究判断</h2>
          <strong>{{ report.finalDecision.code }}</strong>
          <p>{{ report.finalDecision.preferredStrategy }} · {{ report.finalDecision.riskLevel }}</p>
          <p>建议总暴露 {{ percent(report.portfolio.suggestedGrossExposure) }} · confidence {{ percent(report.finalDecision.confidence) }}</p>
          <el-alert title="仅限研究，不构成交易指令" type="warning" :closable="false" show-icon />
        </article>
      </section>

      <section class="panel">
        <h2>工具与 Evidence</h2>
        <div class="facts"><span v-for="call in report.toolCalls" :key="call.callId">{{ call.callId }} · {{ call.toolCode }} · {{ call.status }}</span></div>
        <el-collapse>
          <el-collapse-item v-for="item in report.evidence" :key="item.evidenceId" :title="`${item.evidenceId} · ${item.sourceTool}`">
            <p>{{ item.statement }}</p><small>source {{ shortHash(item.sourceFingerprint) }} · {{ item.observedAt }}</small>
          </el-collapse-item>
        </el-collapse>
      </section>

      <footer class="trace">{{ report.reportVersion }} · {{ report.runtimeVersion }} · fingerprint {{ report.researchFingerprint }}</footer>
    </template>
  </div>
</template>

<style scoped>
.research-workbench{display:grid;gap:18px}.page-head{display:flex;justify-content:space-between;align-items:flex-start;gap:24px}.page-head h1{margin:4px 0 8px;font-size:28px}.page-head p{margin:0;color:#7f8da3}.eyebrow{font-size:12px;letter-spacing:.12em;color:#2bbd85!important}.panel{background:#121b29;border:1px solid #263449;border-radius:12px;padding:18px}.panel h2{margin:0 0 14px;font-size:16px}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.metric{background:linear-gradient(145deg,#132133,#0f1825);border:1px solid #263449;border-radius:10px;padding:16px}.metric span{display:block;color:#8390a5;font-size:12px}.metric strong{display:block;margin-top:8px;color:#e8f0fa}.facts{display:flex;flex-wrap:wrap;gap:9px}.facts span{padding:7px 10px;background:#0d1622;border:1px solid #263449;border-radius:7px;color:#aebbd0;font-size:12px}.agent-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.agent-card{border:1px solid #27364b;border-radius:9px;padding:12px;background:#0e1723}.agent-card>div{display:flex;justify-content:space-between;gap:12px}.agent-card small,.agent-card em{color:#738198;font-size:11px}.agent-card p{line-height:1.55;margin:10px 0 0}.agent-card p b{color:#46c894;margin-right:5px}.agent-card em{display:block}.two-column{display:grid;grid-template-columns:1fr 1fr;gap:12px}.decision>strong{font-size:20px;color:#46c894}.trace{font:11px ui-monospace,SFMono-Regular,Consolas,monospace;color:#66758c;overflow-wrap:anywhere}@media(max-width:1000px){.summary-grid,.agent-grid,.two-column{grid-template-columns:1fr}.page-head{flex-direction:column}}
</style>
