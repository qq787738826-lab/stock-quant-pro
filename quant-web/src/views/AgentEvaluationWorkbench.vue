<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { getAgentEvaluationOverview } from '../agent-evaluation/api'
import type { AgentScorecard, EvaluationOverview } from '../agent-evaluation/types'

const overview = shallowRef<EvaluationOverview | null>(null)
const loading = ref(false)
const error = ref('')
const champion = computed(() => overview.value?.latestReport?.versionEvaluations
  .find(value => value.versionKey === overview.value?.latestReport?.currentChampionVersionKey))
const scorecards = computed<AgentScorecard[]>(() => champion.value?.scorecards ?? [])
const challenger = computed(() => overview.value?.latestReport?.versionEvaluations
  .find(value => value.versionKey === overview.value?.latestReport?.comparison.challengerVersionKey))
const percent = (value?: number) => value == null ? 'N/A' : `${(Number(value) * 100).toFixed(2)}%`

onMounted(async () => {
  loading.value = true
  try { overview.value = await getAgentEvaluationOverview() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : 'Agent evaluation unavailable' }
  finally { loading.value = false }
})
</script>

<template>
  <div class="evaluation" v-loading="loading">
    <header class="hero"><div><p>AGENT_EVALUATION_SYSTEM_V1 / IMMUTABLE EVIDENCE</p><h1>Agent Evaluation</h1><span>Explainable scorecards, immutable versions, Shadow outcomes and bounded champion/challenger decisions.</span></div><el-tag type="warning" effect="dark">NO REAL TRADING</el-tag></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-empty v-else-if="!loading && !overview?.latestReport" description="No frozen M5 evaluation report" />
    <template v-if="overview?.latestReport">
      <section class="metrics">
        <article><span>Champion</span><strong>{{ overview.latestReport.currentChampionVersionKey }}</strong></article>
        <article><span>Shadow samples</span><strong>{{ overview.latestReport.eligibleOutcomeCount }} / {{ overview.realShadowStatus }}</strong></article>
        <article><span>Paper return</span><strong>{{ percent(overview.latestReport.paperTotalReturn) }}</strong></article>
        <article><span>Real trading</span><strong>{{ overview.realTradingEnabled ? 'ENABLED' : 'DISABLED' }}</strong></article>
      </section>
      <section class="panel"><h2>Seven Agent scorecards</h2><el-table :data="scorecards" size="small"><el-table-column prop="role" label="Agent" min-width="210"/><el-table-column prop="weightedScore" label="Score" width="100"/><el-table-column prop="lifecycleDecision" label="Decision" width="120"/><el-table-column prop="reportSampleCount" label="Reports" width="90"/><el-table-column label="Failure modes"><template #default="scope">{{ scope.row.failureModes.join(', ') || 'NONE' }}</template></el-table-column></el-table></section>
      <section class="grid"><article class="panel"><h2>Champion / Challenger</h2><p>Challenger: {{ overview.latestReport.comparison.challengerVersionKey }}</p><strong>{{ overview.latestReport.comparison.decision }}</strong><p>{{ overview.latestReport.comparison.reasons.join(', ') }}</p><p>Score delta: {{ overview.latestReport.comparison.scoreDelta }} / cost ratio: {{ overview.latestReport.comparison.costRatio }} / latency ratio: {{ overview.latestReport.comparison.latencyRatio }}</p><p>Promotion: {{ overview.latestReport.comparison.promotionAllowed ? 'ALLOWED' : 'BLOCKED' }}</p></article><article class="panel"><h2>Confidence calibration</h2><p>Eligible: {{ champion?.calibration.eligibleSampleCount ?? 0 }}</p><p>Abstentions: {{ champion?.calibration.abstentionCount ?? 0 }} (not penalized)</p><p>Brier: {{ champion?.calibration.brierScore ?? 0 }}</p><p>ECE: {{ champion?.calibration.expectedCalibrationError ?? 0 }}</p><p v-if="champion?.calibration.status === 'INSUFFICIENT_SAMPLE'">INSUFFICIENT_SAMPLE is preserved; no long-run claim is manufactured.</p></article></section>
      <section class="panel"><h2>Version registry</h2><el-table :data="overview.registeredVersions" size="small"><el-table-column prop="kind" label="Kind" width="120"/><el-table-column prop="versionKey" label="Version" min-width="240"/><el-table-column prop="parentVersionKey" label="Parent" min-width="220"/><el-table-column prop="modelProvider" label="Provider" width="130"/><el-table-column prop="model" label="Model" min-width="170"/><el-table-column label="Status" width="130"><template #default="scope">{{ scope.row.versionKey === overview.latestReport.currentChampionVersionKey ? 'CHAMPION' : scope.row.versionKey === challenger?.versionKey ? 'CHALLENGER' : 'HISTORICAL' }}</template></el-table-column></el-table></section>
      <section class="panel"><h2>Traceability</h2><p>Offline eval {{ champion?.offlineEvalPassed }}/{{ champion?.offlineEvalTotal }} / replay {{ champion?.historicalReplaySamples }} samples / {{ champion?.modelCalls }} model calls / {{ champion?.totalTokens }} tokens / {{ champion?.accountedCost }} {{ champion?.costCurrency }}</p><p>Frozen history remains bound to its original prompt/model/runtime/tool/strategy fingerprint. M5 cannot overwrite M4 decisions.</p></section>
    </template>
  </div>
</template>

<style scoped>
.evaluation{display:grid;gap:18px}.hero{display:flex;justify-content:space-between;gap:20px}.hero p{color:#d39b3f;font-size:12px;letter-spacing:.12em}.hero h1{margin:5px 0 8px}.hero span,.panel p{color:#8795aa}.metrics,.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.metrics article,.panel{background:#111b29;border:1px solid #28374b;border-radius:12px;padding:17px}.metrics span{display:block;color:#7f8da2;font-size:12px}.metrics strong{display:block;margin-top:8px;overflow-wrap:anywhere}.grid{grid-template-columns:1fr 1fr}.panel h2{margin:0 0 14px;font-size:16px}@media(max-width:900px){.metrics,.grid{grid-template-columns:1fr}.hero{flex-direction:column}}
</style>
