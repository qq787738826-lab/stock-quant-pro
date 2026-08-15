<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAgentResearchReport, getAgentResearchReports } from '../agent-research/api'
import type { ReportSummary, ResearchReport } from '../agent-research/types'
import {
  displayAgentRole,
  displayClaimType,
  displayDecision,
  displayPhase,
  displayReason,
  displayRisk,
  displayStatus,
  displayStrategy,
  displayTool,
  formatDateTime,
} from '../localization/display'

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
    error.value = cause instanceof Error ? displayReason(cause.message) : '研究报告加载失败'
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
    error.value = cause instanceof Error ? displayReason(cause.message) : '研究报告列表加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="research-workbench" v-loading="loading">
    <header class="page-head">
      <div>
        <p class="eyebrow">七智能体研究团队 V1 · 只读</p>
        <h1>智能体研究报告</h1>
        <p>数据、回测和风险指标均来自 M1/M2 确定性工具；本页面不能启动交易或影子研究。</p>
      </div>
      <el-tag type="success" effect="dark">研究输出</el-tag>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-empty v-if="!loading && !error && history.length === 0" description="暂无 M3 脱敏研究报告" />

    <section v-if="history.length" class="panel">
      <h2>研究任务</h2>
      <el-table :data="history" size="small" highlight-current-row @row-click="selectReport">
        <el-table-column prop="taskId" label="任务" min-width="250" />
        <el-table-column label="状态" width="180"><template #default="scope">{{ displayStatus(scope.row.status) }}</template></el-table-column>
        <el-table-column label="研究偏好" min-width="210"><template #default="scope">{{ displayStrategy(scope.row.preferredStrategy) }}</template></el-table-column>
        <el-table-column label="置信度" width="100"><template #default="scope">{{ percent(scope.row.confidence) }}</template></el-table-column>
        <el-table-column label="完成时间" min-width="190"><template #default="scope">{{ formatDateTime(scope.row.completedAt) }}</template></el-table-column>
      </el-table>
    </section>

    <template v-if="report">
      <section class="summary-grid">
        <article class="metric"><span>状态</span><strong>{{ displayStatus(report.status) }}</strong></article>
        <article class="metric"><span>证券 / 开市日</span><strong>{{ report.dataset.securityCount }} / {{ report.dataset.openSessionCount }}</strong></article>
        <article class="metric"><span>工具 / 模型调用</span><strong>{{ report.toolCallCount }} / {{ report.modelCallCount }}</strong></article>
        <article class="metric"><span>研究置信度</span><strong>{{ percent(report.finalDecision.confidence) }}</strong></article>
      </section>

      <section class="panel">
        <h2>任务与数据证据</h2>
        <p>{{ report.task.objective }}</p>
        <div class="facts">
          <span>窗口 {{ report.task.rangeStart }} → {{ report.task.rangeEnd }}</span>
          <span>数据指纹 {{ shortHash(report.dataset.datasetFingerprint) }}</span>
          <span>类型化事实 {{ report.dataset.typedFactReadback ? '通过' : '未通过' }}</span>
          <span>系统知识回读 {{ report.dataset.systemKnowledgeReadback ? '通过' : '未通过' }}</span>
          <span>无未来数据 {{ report.dataset.noFutureDataLeakage ? '通过' : '未通过' }}</span>
          <span>数据源时点边界 {{ report.dataset.providerPitVerified ? '已验证' : '未验证' }}</span>
        </div>
      </section>

      <section class="panel">
        <h2>策略实验与 M2 回测</h2>
        <el-table :data="experiments" size="small">
          <el-table-column label="策略" min-width="250"><template #default="scope">{{ displayStrategy(scope.row.strategyCode) }}</template></el-table-column>
          <el-table-column label="总收益" width="100"><template #default="scope">{{ percent(scope.row.totalReturn) }}</template></el-table-column>
          <el-table-column label="夏普" width="90"><template #default="scope">{{ number(scope.row.sharpeRatio, 3) }}</template></el-table-column>
          <el-table-column label="最大回撤" width="110"><template #default="scope">{{ percent(scope.row.maxDrawdown) }}</template></el-table-column>
          <el-table-column label="超额收益" width="110"><template #default="scope">{{ percent(scope.row.excessReturn) }}</template></el-table-column>
          <el-table-column prop="fillCount" label="成交" width="75" />
          <el-table-column label="样本外" width="80"><template #default="scope">{{ scope.row.outOfSampleEvaluated ? '通过' : '不适用' }}</template></el-table-column>
          <el-table-column prop="walkForwardFolds" label="滚动窗口" width="100" />
          <el-table-column label="过拟合" width="80"><template #default="scope">{{ scope.row.overfittingFlag ? '有风险' : '未发现' }}</template></el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <h2>七智能体协作轨迹</h2>
        <div class="agent-grid">
          <article v-for="run in roleRuns" :key="run.runId" class="agent-card">
            <div><strong>{{ displayAgentRole(run.agentRole) }}</strong><el-tag size="small">{{ displayStatus(run.status) }}</el-tag></div>
            <small>{{ displayPhase(run.phase) }} · 提示词 {{ run.promptVersion }} · {{ run.modelProvider }}/{{ run.model }}</small>
            <p v-for="finding in run.findings" :key="finding.findingId"><b>{{ displayClaimType(finding.claimType) }}</b> {{ finding.statement }}<em>{{ finding.evidenceIds.length }} 条证据</em></p>
          </article>
        </div>
      </section>

      <section class="two-column">
        <article class="panel">
          <h2>批判审查与修正</h2>
          <p><strong>问题：</strong> {{ report.criticReview.issues.join('；') || '无' }}</p>
          <p><strong>返工：</strong> {{ report.criticReview.reworkRequested ? '是' : '否' }} · 修正 {{ report.criticReview.correctionApplied ? '已应用' : '不适用' }}</p>
          <p><strong>限制：</strong> {{ report.portfolio.limitations.join('；') || '无' }}</p>
        </article>
        <article class="panel decision">
          <h2>最终研究判断</h2>
          <strong>{{ displayDecision(report.finalDecision.code) }}</strong>
          <p>{{ displayStrategy(report.finalDecision.preferredStrategy) }} · {{ displayRisk(report.finalDecision.riskLevel) }}</p>
          <p>建议总仓位 {{ percent(report.portfolio.suggestedGrossExposure) }} · 置信度 {{ percent(report.finalDecision.confidence) }}</p>
          <el-alert title="仅限研究，不构成交易指令" type="warning" :closable="false" show-icon />
        </article>
      </section>

      <section class="panel">
        <h2>工具与证据</h2>
        <div class="facts"><span v-for="call in report.toolCalls" :key="call.callId">{{ call.callId }} · {{ displayTool(call.toolCode) }} · {{ displayStatus(call.status) }}</span></div>
        <el-collapse>
          <el-collapse-item v-for="item in report.evidence" :key="item.evidenceId" :title="`${item.evidenceId} · ${item.sourceTool}`">
            <p>{{ item.statement }}</p><small>来源 {{ shortHash(item.sourceFingerprint) }} · {{ formatDateTime(item.observedAt) }}</small>
          </el-collapse-item>
        </el-collapse>
      </section>

      <footer class="trace">{{ report.reportVersion }} · {{ report.runtimeVersion }} · 指纹 {{ report.researchFingerprint }}</footer>
    </template>
  </div>
</template>

<style scoped>
.research-workbench{display:grid;gap:18px}.page-head{display:flex;justify-content:space-between;align-items:flex-start;gap:24px}.page-head h1{margin:4px 0 8px;font-size:28px}.page-head p{margin:0;color:#7f8da3}.eyebrow{font-size:12px;letter-spacing:.12em;color:#2bbd85!important}.panel{background:#121b29;border:1px solid #263449;border-radius:12px;padding:18px}.panel h2{margin:0 0 14px;font-size:16px}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.metric{background:linear-gradient(145deg,#132133,#0f1825);border:1px solid #263449;border-radius:10px;padding:16px}.metric span{display:block;color:#8390a5;font-size:12px}.metric strong{display:block;margin-top:8px;color:#e8f0fa}.facts{display:flex;flex-wrap:wrap;gap:9px}.facts span{padding:7px 10px;background:#0d1622;border:1px solid #263449;border-radius:7px;color:#aebbd0;font-size:12px}.agent-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.agent-card{border:1px solid #27364b;border-radius:9px;padding:12px;background:#0e1723}.agent-card>div{display:flex;justify-content:space-between;gap:12px}.agent-card small,.agent-card em{color:#738198;font-size:11px}.agent-card p{line-height:1.55;margin:10px 0 0}.agent-card p b{color:#46c894;margin-right:5px}.agent-card em{display:block}.two-column{display:grid;grid-template-columns:1fr 1fr;gap:12px}.decision>strong{font-size:20px;color:#46c894}.trace{font:11px ui-monospace,SFMono-Regular,Consolas,monospace;color:#66758c;overflow-wrap:anywhere}@media(max-width:1000px){.summary-grid,.agent-grid,.two-column{grid-template-columns:1fr}.page-head{flex-direction:column}}
</style>
