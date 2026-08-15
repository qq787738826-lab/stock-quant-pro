<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { getAgentEvaluationOverview } from '../agent-evaluation/api'
import type { AgentScorecard, EvaluationOverview } from '../agent-evaluation/types'
import {
  displayAgentRole,
  displayDecision,
  displayReason,
  displayStatus,
} from '../localization/display'

const overview = shallowRef<EvaluationOverview | null>(null)
const loading = ref(false)
const error = ref('')
const champion = computed(() => overview.value?.latestReport?.versionEvaluations
  .find(value => value.versionKey === overview.value?.latestReport?.currentChampionVersionKey))
const scorecards = computed<AgentScorecard[]>(() => champion.value?.scorecards ?? [])
const challenger = computed(() => overview.value?.latestReport?.versionEvaluations
  .find(value => value.versionKey === overview.value?.latestReport?.comparison.challengerVersionKey))
const percent = (value?: number) => value == null ? '—' : `${(Number(value) * 100).toFixed(2)}%`

onMounted(async () => {
  loading.value = true
  try { overview.value = await getAgentEvaluationOverview() }
  catch (cause) { error.value = cause instanceof Error ? displayReason(cause.message) : '智能体评测暂不可用' }
  finally { loading.value = false }
})
</script>

<template>
  <div class="evaluation" v-loading="loading">
    <header class="hero"><div><p>智能体评测系统 V1 / 不可变证据</p><h1>智能体评测</h1><span>可解释评分卡、不可变版本、影子研究结果与受控的正式版/候选版决策。</span></div><el-tag type="warning" effect="dark">禁止真实交易</el-tag></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-empty v-else-if="!loading && !overview?.latestReport" description="暂无已冻结的智能体评测报告" />
    <template v-if="overview?.latestReport">
      <section class="metrics">
        <article><span>当前正式版本</span><strong>{{ overview.latestReport.currentChampionVersionKey }}</strong></article>
        <article><span>影子研究样本</span><strong>{{ overview.latestReport.eligibleOutcomeCount }} / {{ displayDecision(overview.realShadowStatus) }}</strong></article>
        <article><span>模拟收益</span><strong>{{ percent(overview.latestReport.paperTotalReturn) }}</strong></article>
        <article><span>真实交易</span><strong>{{ overview.realTradingEnabled ? '已启用' : '已关闭' }}</strong></article>
      </section>
      <section class="panel"><h2>七智能体评分卡</h2><el-table :data="scorecards" size="small"><el-table-column label="智能体" min-width="210"><template #default="scope">{{ displayAgentRole(scope.row.role) }}</template></el-table-column><el-table-column prop="weightedScore" label="评分" width="100"/><el-table-column label="评估结论" width="120"><template #default="scope">{{ displayStatus(scope.row.lifecycleDecision) }}</template></el-table-column><el-table-column prop="reportSampleCount" label="报告数" width="90"/><el-table-column label="主要问题"><template #default="scope"><span v-for="mode in scope.row.failureModes" :key="mode" :title="`高级诊断：${mode}`">{{ displayReason(mode) }}；</span><span v-if="!scope.row.failureModes.length">无</span></template></el-table-column></el-table></section>
      <section class="grid"><article class="panel"><h2>正式版本 / 候选版本</h2><p>候选版本：{{ overview.latestReport.comparison.challengerVersionKey }}</p><strong>{{ displayDecision(overview.latestReport.comparison.decision) }}</strong><p><span v-for="reason in overview.latestReport.comparison.reasons" :key="reason" :title="`高级诊断：${reason}`">{{ displayReason(reason) }}；</span></p><p>评分差：{{ overview.latestReport.comparison.scoreDelta }} / 成本比：{{ overview.latestReport.comparison.costRatio }} / 延迟比：{{ overview.latestReport.comparison.latencyRatio }}</p><p>允许晋升：{{ overview.latestReport.comparison.promotionAllowed ? '是' : '否' }}</p></article><article class="panel"><h2>置信度校准</h2><p>有效样本：{{ champion?.calibration.eligibleSampleCount ?? 0 }}</p><p>放弃判断：{{ champion?.calibration.abstentionCount ?? 0 }}（不扣分）</p><p>布里尔分数：{{ champion?.calibration.brierScore ?? 0 }}</p><p>期望校准误差：{{ champion?.calibration.expectedCalibrationError ?? 0 }}</p><p v-if="champion?.calibration.status === 'INSUFFICIENT_SAMPLE'">当前样本不足；系统不会制造长期有效性结论。</p></article></section>
      <section class="panel"><h2>版本登记</h2><el-table :data="overview.registeredVersions" size="small"><el-table-column label="类型" width="120"><template #default="scope">{{ displayDecision(scope.row.kind) }}</template></el-table-column><el-table-column prop="versionKey" label="版本" min-width="240"/><el-table-column prop="parentVersionKey" label="上级版本" min-width="220"/><el-table-column prop="modelProvider" label="模型服务" width="130"/><el-table-column prop="model" label="模型" min-width="170"/><el-table-column label="状态" width="130"><template #default="scope">{{ displayDecision(scope.row.versionKey === overview.latestReport.currentChampionVersionKey ? 'CHAMPION' : scope.row.versionKey === challenger?.versionKey ? 'CHALLENGER' : 'HISTORICAL') }}</template></el-table-column></el-table></section>
      <section class="panel"><h2>可追溯性</h2><p>离线评测 {{ champion?.offlineEvalPassed }}/{{ champion?.offlineEvalTotal }} / 历史回放 {{ champion?.historicalReplaySamples }} 个样本 / {{ champion?.modelCalls }} 次模型调用 / {{ champion?.totalTokens }} 个令牌 / {{ champion?.accountedCost }} {{ champion?.costCurrency }}</p><p>冻结历史始终绑定原始提示词、模型、运行时、工具和策略指纹；新评测不能覆盖既有影子研究决策。</p></section>
    </template>
  </div>
</template>

<style scoped>
.evaluation{display:grid;gap:18px}.hero{display:flex;justify-content:space-between;gap:20px}.hero p{color:#d39b3f;font-size:12px;letter-spacing:.12em}.hero h1{margin:5px 0 8px}.hero span,.panel p{color:#8795aa}.metrics,.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.metrics article,.panel{background:#111b29;border:1px solid #28374b;border-radius:12px;padding:17px}.metrics span{display:block;color:#7f8da2;font-size:12px}.metrics strong{display:block;margin-top:8px;overflow-wrap:anywhere}.grid{grid-template-columns:1fr 1fr}.panel h2{margin:0 0 14px;font-size:16px}@media(max-width:900px){.metrics,.grid{grid-template-columns:1fr}.hero{flex-direction:column}}
</style>
