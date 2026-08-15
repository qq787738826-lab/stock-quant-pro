<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { getShadowResearchOverview, getShadowResearchRun } from '../shadow-research/api'
import type { ShadowOverview, ShadowRun, ShadowRunDetail } from '../shadow-research/types'
import {
  displayAgentRole,
  displayClaimType,
  displayDecision,
  displayPhase,
  displayReason,
  displayResearchList,
  displayResearchText,
  displayRisk,
  displayStatus,
  displayStrategy,
  displayTrigger,
  displayValue,
  formatCurrency,
  formatDateTime,
  isLegacyResearchText,
} from '../localization/display'

const overview = shallowRef<ShadowOverview | null>(null)
const detail = shallowRef<ShadowRunDetail | null>(null)
const loading = ref(false)
const error = ref('')
const report = computed(() => detail.value?.snapshot?.report)
const legacyReport = computed(() => report.value?.agentRuns.some(run =>
  run.findings.some(finding => isLegacyResearchText(finding.statement))) ?? false)

const money = (value?: number) => value == null ? '—' : formatCurrency(value)
const percent = (value?: number) => value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`
const short = (value?: string) => value ? `${value.slice(0, 12)}…` : '—'

async function select(run: ShadowRun) {
  loading.value = true
  error.value = ''
  try { detail.value = await getShadowResearchRun(run.id) }
  catch (cause) { error.value = cause instanceof Error ? displayReason(cause.message) : '影子研究详情加载失败' }
  finally { loading.value = false }
}

onMounted(async () => {
  loading.value = true
  try {
    overview.value = await getShadowResearchOverview()
    const first = overview.value.runs[0]
    if (first) await select(first)
  } catch (cause) { error.value = cause instanceof Error ? displayReason(cause.message) : '影子研究历史加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <div class="shadow-page" v-loading="loading">
    <header class="hero">
      <div><p>影子研究运行时 V1 · 仅模拟</p><h1>影子研究</h1><span>冻结当时可见的数据、智能体判断与模拟执行；不连接券商，不产生真实订单。</span></div>
      <el-tag type="warning" effect="dark">研究 / 模拟</el-tag>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <section v-if="overview" class="metrics">
      <article><span>模拟权益</span><strong>{{ money(overview.latestPortfolioSnapshot?.totalEquity ?? overview.portfolio.cash) }}</strong></article>
      <article><span>模拟收益</span><strong>{{ percent(overview.latestPortfolioSnapshot?.totalReturn) }}</strong></article>
      <article><span>模拟持仓</span><strong>{{ overview.portfolio.positions.length }}</strong></article>
      <article><span>真实交易</span><strong>{{ overview.realTradingEnabled ? '已启用' : '已关闭' }}</strong></article>
    </section>

    <section v-if="overview" class="panel">
      <h2>冻结运行历史</h2>
      <el-table :data="overview.runs" size="small" highlight-current-row @row-click="select">
        <el-table-column prop="tradeDate" label="交易日" width="120" />
        <el-table-column label="触发" width="150"><template #default="scope">{{ displayTrigger(scope.row.triggerMode) }}</template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="scope">{{ displayStatus(scope.row.status) }}</template></el-table-column>
        <el-table-column prop="model" label="模型" width="150" />
        <el-table-column label="数据指纹"><template #default="scope">{{ short(scope.row.datasetFingerprint) }}</template></el-table-column>
        <el-table-column label="冻结时间" min-width="190"><template #default="scope">{{ formatDateTime(scope.row.completedAt) }}</template></el-table-column>
      </el-table>
    </section>

    <template v-if="detail">
      <section class="grid">
        <article class="panel"><h2>时间边界</h2><p>研究时点 <b>{{ formatDateTime(detail.run.researchAsOf) }}</b></p><p>信号时点 <b>{{ formatDateTime(detail.run.signalTime) }}</b></p><p>模拟执行时点 <b>{{ detail.run.paperExecutionTime ? formatDateTime(detail.run.paperExecutionTime) : '待下一合法时点' }}</b></p></article>
        <article class="panel"><h2>冻结结论</h2><template v-if="detail.snapshot"><strong class="decision">{{ displayDecision(detail.snapshot.recommendation.decisionCode) }}</strong><p>{{ displayStrategy(detail.snapshot.recommendation.preferredStrategy) }} · {{ displayRisk(detail.snapshot.recommendation.riskLevel) }}</p><p>置信度 {{ percent(detail.snapshot.recommendation.confidence) }} · 模拟仓位 {{ percent(detail.snapshot.recommendation.suggestedGrossExposure) }}</p></template><p v-else>本次运行未冻结结论：{{ displayReason(detail.run.errorCode) }}</p></article>
      </section>

      <section v-if="report" class="panel">
        <h2>七智能体与批判审查</h2>
        <el-alert v-if="legacyReport" title="历史原始报告：原始英文研究正文保持不可变；新影子研究默认使用简体中文。" type="info" :closable="false" show-icon />
        <div class="agents"><article v-for="run in report.agentRuns" :key="run.runId"><b>{{ displayAgentRole(run.agentRole) }}</b><span>{{ displayPhase(run.phase) }} · {{ displayStatus(run.status) }}</span><p v-for="finding in run.findings" :key="finding.findingId">{{ displayClaimType(finding.claimType) }} · {{ displayResearchText(finding.statement) }}</p></article></div>
        <p class="critic">批判审查：{{ displayResearchList(report.criticReview.issues) || '未发现阻断性问题' }} · 修正 {{ report.criticReview.correctionApplied ? '已应用' : '不适用' }}</p>
      </section>

      <section v-if="detail.snapshot" class="grid">
        <article class="panel"><h2>候选与限制</h2><p>候选排名：{{ detail.snapshot.recommendation.rankedSecurities.join(' · ') || '无候选' }}</p><p v-for="item in detail.snapshot.recommendation.limitations" :key="item">• {{ displayResearchText(item) }}</p></article>
        <article class="panel"><h2>证据</h2><p>{{ report?.evidence.length ?? 0 }} 项 · 类型化事实 / 系统知识 / 前复权</p><p>快照 {{ short(detail.snapshot.snapshotFingerprint) }}</p><p>研究 {{ short(detail.run.researchFingerprint) }}</p></article>
      </section>

      <section class="panel"><h2>模拟订单与成交</h2><el-table :data="detail.orders" size="small"><el-table-column label="方向" width="80"><template #default="scope">{{ displayValue(scope.row.side) }}</template></el-table-column><el-table-column label="证券"><template #default="scope">{{ scope.row.security.symbol }} / {{ scope.row.security.exchange }}</template></el-table-column><el-table-column prop="targetWeight" label="目标权重"/><el-table-column label="最早模拟成交"><template #default="scope">{{ formatDateTime(scope.row.earliestExecutionTime) }}</template></el-table-column><el-table-column label="状态"><template #default="scope">{{ displayStatus(scope.row.status) }}</template></el-table-column></el-table><p v-if="!detail.orders.length">空仓是合法决策；本次没有模拟订单。</p></section>
      <section class="panel"><h2>后续结果</h2><el-table :data="detail.outcomes" size="small"><el-table-column prop="horizonCode" label="观察期" width="100"/><el-table-column prop="evaluationDate" label="评价日期" width="140"/><el-table-column label="等权结果"><template #default="scope">{{ percent(scope.row.observation.equalWeightReturn) }}</template></el-table-column><el-table-column label="时间边界"><template #default="scope">{{ scope.row.observation.noFutureDataLeakage ? '通过' : '受阻' }}</template></el-table-column></el-table><p v-if="!detail.outcomes.length">未来观察尚未到期；冻结结论不会被回写。</p></section>
    </template>
  </div>
</template>

<style scoped>
.shadow-page{display:grid;gap:18px}.hero{display:flex;justify-content:space-between;gap:24px}.hero p{color:#d39b3f;font-size:12px;letter-spacing:.12em}.hero h1{margin:4px 0 8px}.hero span{color:#8795aa}.metrics,.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.metrics article,.panel{background:#111b29;border:1px solid #28374b;border-radius:12px;padding:17px}.metrics span{display:block;color:#7f8da2;font-size:12px}.metrics strong{display:block;margin-top:8px}.panel h2{margin:0 0 14px;font-size:16px}.grid{grid-template-columns:1fr 1fr}.decision{color:#4fd19a;font-size:20px}.agents{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.agents article{padding:12px;border:1px solid #27364a;border-radius:9px;background:#0d1622}.agents span{display:block;color:#78879d;font-size:11px;margin-top:4px}.agents p{font-size:12px;line-height:1.5}.critic{color:#e4b45f}.panel>p{color:#aab6c8}@media(max-width:900px){.metrics,.grid,.agents{grid-template-columns:1fr}.hero{flex-direction:column}}
</style>
