<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { getShadowResearchOverview, getShadowResearchRun } from '../shadow-research/api'
import type { ShadowOverview, ShadowRun, ShadowRunDetail } from '../shadow-research/types'

const overview = shallowRef<ShadowOverview | null>(null)
const detail = shallowRef<ShadowRunDetail | null>(null)
const loading = ref(false)
const error = ref('')
const report = computed(() => detail.value?.snapshot?.report)

const money = (value?: number) => value == null ? '—' : `¥${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
const percent = (value?: number) => value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`
const short = (value?: string) => value ? `${value.slice(0, 12)}…` : '—'

async function select(run: ShadowRun) {
  loading.value = true
  error.value = ''
  try { detail.value = await getShadowResearchRun(run.id) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Shadow 详情加载失败' }
  finally { loading.value = false }
}

onMounted(async () => {
  loading.value = true
  try {
    overview.value = await getShadowResearchOverview()
    const first = overview.value.runs[0]
    if (first) await select(first)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : 'Shadow 历史加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <div class="shadow-page" v-loading="loading">
    <header class="hero">
      <div><p>SHADOW_RESEARCH_RUNTIME_V1 · PAPER ONLY</p><h1>影子研究</h1><span>冻结当时可见的数据、Agent 判断与模拟执行；不连接券商，不产生真实订单。</span></div>
      <el-tag type="warning" effect="dark">RESEARCH / PAPER</el-tag>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <section v-if="overview" class="metrics">
      <article><span>Paper Equity</span><strong>{{ money(overview.latestPortfolioSnapshot?.totalEquity ?? overview.portfolio.cash) }}</strong></article>
      <article><span>Paper Return</span><strong>{{ percent(overview.latestPortfolioSnapshot?.totalReturn) }}</strong></article>
      <article><span>Positions</span><strong>{{ overview.portfolio.positions.length }}</strong></article>
      <article><span>Real Trading</span><strong>{{ overview.realTradingEnabled ? 'ENABLED' : 'DISABLED' }}</strong></article>
    </section>

    <section v-if="overview" class="panel">
      <h2>冻结运行历史</h2>
      <el-table :data="overview.runs" size="small" highlight-current-row @row-click="select">
        <el-table-column prop="tradeDate" label="交易日" width="120" />
        <el-table-column prop="triggerMode" label="触发" width="150" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="model" label="模型" width="150" />
        <el-table-column label="Dataset"><template #default="scope">{{ short(scope.row.datasetFingerprint) }}</template></el-table-column>
        <el-table-column prop="completedAt" label="冻结时间" min-width="190" />
      </el-table>
    </section>

    <template v-if="detail">
      <section class="grid">
        <article class="panel"><h2>时间边界</h2><p>researchAsOf <b>{{ detail.run.researchAsOf }}</b></p><p>signalTime <b>{{ detail.run.signalTime ?? '—' }}</b></p><p>paperExecutionTime <b>{{ detail.run.paperExecutionTime ?? '待下一合法时点' }}</b></p></article>
        <article class="panel"><h2>冻结结论</h2><template v-if="detail.snapshot"><strong class="decision">{{ detail.snapshot.recommendation.decisionCode }}</strong><p>{{ detail.snapshot.recommendation.preferredStrategy }} · {{ detail.snapshot.recommendation.riskLevel }}</p><p>confidence {{ percent(detail.snapshot.recommendation.confidence) }} · paper exposure {{ percent(detail.snapshot.recommendation.suggestedGrossExposure) }}</p></template><p v-else>本次运行未冻结结论：{{ detail.run.errorCode }}</p></article>
      </section>

      <section v-if="report" class="panel">
        <h2>7 Agent 与 Critic</h2>
        <div class="agents"><article v-for="run in report.agentRuns" :key="run.runId"><b>{{ run.agentRole }}</b><span>{{ run.phase }} · {{ run.status }}</span><p v-for="finding in run.findings" :key="finding.findingId">{{ finding.claimType }} · {{ finding.statement }}</p></article></div>
        <p class="critic">Critic: {{ report.criticReview.issues.join(', ') || 'NONE' }} · correction {{ report.criticReview.correctionApplied ? 'APPLIED' : 'N/A' }}</p>
      </section>

      <section v-if="detail.snapshot" class="grid">
        <article class="panel"><h2>候选与限制</h2><p>Ranking: {{ detail.snapshot.recommendation.rankedSecurities.join(' · ') || 'EMPTY' }}</p><p v-for="item in detail.snapshot.recommendation.limitations" :key="item">• {{ item }}</p></article>
        <article class="panel"><h2>Evidence</h2><p>{{ report?.evidence.length ?? 0 }} items · typed facts / SYSTEM_KNOWLEDGE / QFQ</p><p>snapshot {{ short(detail.snapshot.snapshotFingerprint) }}</p><p>research {{ short(detail.run.researchFingerprint) }}</p></article>
      </section>

      <section class="panel"><h2>Paper Orders / Fills</h2><el-table :data="detail.orders" size="small"><el-table-column prop="side" label="方向" width="80"/><el-table-column label="证券"><template #default="scope">{{ scope.row.security.symbol }} / {{ scope.row.security.exchange }}</template></el-table-column><el-table-column prop="targetWeight" label="目标权重"/><el-table-column prop="earliestExecutionTime" label="最早模拟成交"/><el-table-column prop="status" label="状态"/></el-table><p v-if="!detail.orders.length">空仓是合法决策；本次没有模拟订单。</p></section>
      <section class="panel"><h2>Future Outcome</h2><el-table :data="detail.outcomes" size="small"><el-table-column prop="horizonCode" label="观察期" width="100"/><el-table-column prop="evaluationDate" label="评价日期" width="140"/><el-table-column label="等权结果"><template #default="scope">{{ percent(scope.row.observation.equalWeightReturn) }}</template></el-table-column><el-table-column label="时间边界"><template #default="scope">{{ scope.row.observation.noFutureDataLeakage ? 'PASS' : 'BLOCKED' }}</template></el-table-column></el-table><p v-if="!detail.outcomes.length">未来观察尚未到期；冻结结论不会被回写。</p></section>
    </template>
  </div>
</template>

<style scoped>
.shadow-page{display:grid;gap:18px}.hero{display:flex;justify-content:space-between;gap:24px}.hero p{color:#d39b3f;font-size:12px;letter-spacing:.12em}.hero h1{margin:4px 0 8px}.hero span{color:#8795aa}.metrics,.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.metrics article,.panel{background:#111b29;border:1px solid #28374b;border-radius:12px;padding:17px}.metrics span{display:block;color:#7f8da2;font-size:12px}.metrics strong{display:block;margin-top:8px}.panel h2{margin:0 0 14px;font-size:16px}.grid{grid-template-columns:1fr 1fr}.decision{color:#4fd19a;font-size:20px}.agents{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.agents article{padding:12px;border:1px solid #27364a;border-radius:9px;background:#0d1622}.agents span{display:block;color:#78879d;font-size:11px;margin-top:4px}.agents p{font-size:12px;line-height:1.5}.critic{color:#e4b45f}.panel>p{color:#aab6c8}@media(max-width:900px){.metrics,.grid,.agents{grid-template-columns:1fr}.hero{flex-direction:column}}
</style>
