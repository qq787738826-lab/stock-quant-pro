<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getResearchUniverse, getSelectionHistory, getSelectionRun, startSelection } from '../research-selection/api'
import type { SelectionResult, SelectionSummary } from '../research-selection/types'

const result = ref<SelectionResult | null>(null)
const history = ref<SelectionSummary[]>([])
const universe = ref<any>(null)
const running = ref(false)
const error = ref('')
const diagnosticReason = ref('')
const selectedWindow = ref(20)
const activeRunId = ref<number | null>(null)
const route = useRoute()
const router = useRouter()
let timer: number | undefined
const stages = ['准备数据', '量化扫描', '策略分析', 'AI研究', 'Critic审查', '完成']
const currentStage = computed(() => {
  const value = history.value.find(item => item.runId === activeRunId.value)
    ?.status || ''
  return ({ QUEUED: 0, PREPARING_DATA: 0, QUANTITATIVE_SCAN: 1,
    STRATEGY_ANALYSIS: 2, AI_RESEARCH: 3, CRITIC_REVIEW: 4,
    COMPLETED: 5 } as Record<string, number>)[value] ?? 0
})

async function load() {
  [history.value, universe.value] = await Promise.all([
    getSelectionHistory(), getResearchUniverse()
  ])
  const requestedId = Number(route.query.run)
  const latest = Number.isSafeInteger(requestedId) && requestedId > 0
    ? await getSelectionRun(requestedId) as SelectionSummary
    : history.value[0]
  if (latest?.status === 'COMPLETED') {
    result.value = await getSelectionRun(latest.runId) as SelectionResult
  } else if (latest && latest.status !== 'FAILED') {
    running.value = true
    activeRunId.value = latest.runId
    poll(latest.runId)
  }
}
async function openRun(id: number) {
  const value = await getSelectionRun(id)
  if ('contractVersion' in value && value.status === 'COMPLETED') result.value = value
}
async function start() {
  running.value = true; error.value = ''; diagnosticReason.value = ''; result.value = null
  try {
    const accepted = await startSelection(selectedWindow.value)
    updateHistory(accepted.run)
    activeRunId.value = accepted.run.runId
    await router.replace({ path: '/research-selection', query: {
      run: String(accepted.run.runId)
    } })
    poll(accepted.run.runId)
  } catch (cause) {
    running.value = false
    error.value = startFriendly(cause)
    diagnosticReason.value = safeDiagnostic(cause instanceof Error ? cause.message : '')
  }
}
function poll(id: number) {
  window.clearInterval(timer)
  timer = window.setInterval(async () => {
    try {
      const value: any = await getSelectionRun(id)
      updateHistory(value)
      if (value.status === 'COMPLETED') {
        result.value = value; running.value = false; activeRunId.value = null
        window.clearInterval(timer)
      } else if (value.status === 'FAILED') {
        error.value = friendly(value.failureCategory, value.failureReason)
        diagnosticReason.value = safeDiagnostic(value.failureReason)
        running.value = false; activeRunId.value = null
        window.clearInterval(timer)
      }
    } catch { /* transient refresh failure is retried */ }
  }, 2000)
}
function updateHistory(value: SelectionSummary) {
  const index = history.value.findIndex(item => item.runId === value.runId)
  if (index >= 0) history.value[index] = value
  else history.value = [value, ...history.value]
}
function friendly(category?: string, reason?: string) {
  if (category === 'BUDGET') return 'API预算不足，本次未继续消耗。'
  if (category === 'DATA') return '研究数据暂未准备完整，本次未生成强制候选。'
  if (category === 'PROVIDER') return 'Tushare数据服务暂不可用，本次研究已安全停止。'
  if (category === 'MODEL') return '百炼AI暂不可用，本次研究已安全停止。'
  if (category === 'DATABASE') return '本地研究数据库暂不可用，系统正在等待恢复。'
  if (category === 'BUILD') return '选股运行文件与当前版本不一致，请等待受控更新完成。'
  if (category === 'BROKER') return '本地研究服务暂不可用，请等待系统自动恢复。'
  return reason ? '研究未能完成，系统已安全停止。' : '研究暂不可用，请稍后重试。'
}
function startFriendly(cause: unknown) {
  const message = cause instanceof Error ? cause.message : ''
  if (message.includes('RESEARCH_SELECTION_ALREADY_RUNNING')) {
    return '已有一次立即选股正在进行，请查看当前进度。'
  }
  if (message.startsWith('BUDGET:')) return 'API预算不足，本次未启动。'
  if (message.startsWith('DATA:')) return '数据准备暂不可用，本次未启动。'
  if (message.startsWith('MODEL:')) return 'AI暂不可用，本次未启动。'
  if (message.includes('TUSHARE') || message.includes('PROVIDER')) {
    return 'Tushare数据服务暂不可用，本次未启动。'
  }
  if (message.includes('BUILD') || message.includes('ARTIFACT') || message.includes('JAR')) {
    return '选股运行文件与当前版本不一致，请等待受控更新完成。'
  }
  if (message.startsWith('BROKER:') || message.startsWith('BUILD:')) {
    return '本地研究服务暂不可用，请等待系统自动恢复。'
  }
  return '研究启动失败，系统未产生真实交易。'
}
function safeDiagnostic(value?: string) {
  const match = String(value || '').match(/[A-Z][A-Z0-9_]{3,127}/)
  return match?.[0] || ''
}
const pct = (v: number) => `${(Number(v) * 100).toFixed(2)}%`
onMounted(load)
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div class="selection-page">
    <header class="selection-hero">
      <div><p>RESEARCH_UNIVERSE_V1 · CURRENT AS-OF</p><h1>立即选股</h1>
        <span>确定性扫描 {{ universe?.size || 25 }} 只沪深主板股票，Top 10 进入 7-Agent 深度研究。</span></div>
      <div class="selection-action"><select v-model="selectedWindow" :disabled="running">
        <option :value="20">最近20交易日</option><option :value="60">最近60交易日</option>
        <option :value="120">最近120交易日</option><option :value="250">最近250交易日</option>
      </select><button class="select-now" :disabled="running" @click="start">{{ running ? '研究进行中…' : '立即选股' }}</button></div>
    </header>
    <div class="boundary">研究与 Paper 模拟用途 · 真实交易 OFF · 当前分析不是 Historical Live Shadow</div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <details v-if="error && diagnosticReason" class="diagnostic"><summary>高级诊断信息</summary><code>{{ diagnosticReason }}</code></details>
    <div v-if="running" class="stage-track"><div v-for="(stage, index) in stages" :key="stage" :class="{ active: index <= currentStage }"><i>{{ index + 1 }}</i><span>{{ stage }}</span></div></div>

    <template v-if="result">
      <section class="summary-strip"><article><span>分析窗口</span><b>{{ result.dataCoverage.rangeStart }} → {{ result.dataCoverage.rangeEnd }}</b></article><article><span>完整股票</span><b>{{ result.dataCoverage.completeSecurityCount }} / {{ result.dataCoverage.securityCount }}</b></article><article><span>总耗时</span><b>{{ (result.timings.totalMillis / 1000).toFixed(1) }}s</b></article><article><span>本次成本</span><b>¥{{ result.usage.conservativeCostCny }}</b></article></section>
      <section class="result-panel"><h2>今日选股结果</h2>
        <div v-if="result.emptyResult" class="empty-result"><strong>今日无合格候选</strong><span>INSUFFICIENT_EVIDENCE · 系统没有为了展示效果强制推荐股票。</span></div>
        <article v-for="candidate in result.candidates" :key="candidate.security.symbol" class="candidate-card">
          <div class="candidate-rank">{{ candidate.rank }}</div><div class="candidate-main"><h3>{{ candidate.name }} <small>{{ candidate.security.symbol }} / {{ candidate.security.exchange }}</small></h3><div class="candidate-tags"><b>综合 {{ candidate.quantitativeScore }}</b><span>风险 {{ candidate.riskLevel }}</span><span>Confidence {{ pct(candidate.confidence) }}</span><span>{{ candidate.recommendation }}</span></div><p><strong>量化：</strong>{{ candidate.supportingReasons.join('；') }}</p><p><strong>Agent：</strong>{{ result.agentReport.finalDecision.code }} · {{ result.agentReport.finalDecision.supportingEvidenceIds.length }} 条支持证据</p><p><strong>Strategy：</strong>{{ candidate.preferredStrategy }} · 最大回撤 {{ pct(candidate.maxDrawdown) }} · {{ candidate.trend }}</p><div class="strategy-row"><span v-for="strategy in candidate.strategyComparison" :key="strategy.strategyCode"><b>{{ strategy.strategyCode }}</b> 收益 {{ pct(strategy.totalReturn) }} · Sharpe {{ Number(strategy.sharpeRatio).toFixed(2) }} · 回撤 {{ pct(strategy.maxDrawdown) }}</span></div><p><strong>Critic：</strong>{{ candidate.criticIssues.length ? candidate.criticIssues.join('；') : '未发现阻断性问题' }}</p><p class="opposing"><strong>限制：</strong>{{ candidate.opposingReasons.join('；') || '无额外限制' }}</p></div>
        </article>
      </section>
      <section class="result-grid"><article><h2>量化 Top 10</h2><table><thead><tr><th>#</th><th>股票</th><th>行业</th><th>评分</th><th>20日</th><th>60日</th><th>Sharpe</th><th>回撤</th></tr></thead><tbody><tr v-for="score in result.shortlist" :key="score.security.symbol"><td>{{ score.rank }}</td><td>{{ score.name }}<small>{{ score.security.symbol }}</small></td><td>{{ score.industry }}</td><td>{{ score.score }}</td><td>{{ pct(score.twentyDayReturn) }}</td><td>{{ pct(score.sixtyDayReturn) }}</td><td>{{ score.sharpe }}</td><td>{{ pct(score.maxDrawdown) }}</td></tr></tbody></table></article><article><h2>研究血缘与用量</h2><p>Universe：{{ result.lineage.researchUniverseVersion }}</p><p>Ranking：{{ result.lineage.rankingVersion }}</p><p>Model：{{ result.lineage.modelProvider }} / {{ result.lineage.model }}</p><p>Agent：{{ result.usage.modelCalls }} calls / {{ result.usage.totalTokens }} tokens</p><p>Tushare：{{ result.usage.tushareProviderRequests }} calls / retry {{ result.usage.retryCount }}</p><p>PIT / knownAt：{{ result.dataCoverage.noFutureDataLeakage ? 'PASS' : 'FAILED' }}</p><router-link to="/shadow-research">查看冻结研究与 Paper →</router-link></article></section>
      <section class="agent-panel"><h2>7-Agent 研究结论</h2><div class="agent-grid"><article v-for="agent in result.agentReport.agentRuns" :key="agent.runId"><header><b>{{ agent.agentRole }}</b><span>{{ agent.status }}</span></header><p v-for="finding in agent.findings" :key="finding.findingId"><em>{{ finding.claimType }}</em>{{ finding.statement }}</p><small v-if="!agent.findings.length">本轮没有可证据化的新结论。</small></article></div><div class="critic-box"><b>Critic 审查</b><span>{{ result.agentReport.criticReview.issues.length ? result.agentReport.criticReview.issues.join('；') : '未发现阻断性问题' }}</span><small>修正：{{ result.agentReport.criticReview.correctionApplied ? '已应用' : '无需修正' }} · 返工 {{ result.agentReport.criticReview.reworkRounds }} 轮</small></div><div class="paper-box"><b>Paper 模拟：{{ result.paperEnabled ? 'ON' : 'OFF' }}</b><span>{{ result.shadowRunId ? '研究结论已冻结并进入 Paper 流程' : '本次未生成 Paper 记录' }}；真实交易始终 OFF。</span></div></section>
    </template>

    <section class="history-panel"><h2>历史选股结果</h2><table><thead><tr><th>时间</th><th>锚点</th><th>状态</th><th>股票池</th><th>Top10</th><th>候选</th><th>结论</th></tr></thead><tbody><tr v-for="item in history" :key="item.runId" class="history-row" @click="openRun(item.runId)"><td>{{ item.createdAt }}</td><td>{{ item.anchorTradeDate || '准备中' }}</td><td>{{ item.status }}</td><td>{{ item.universeSize }}</td><td>{{ item.shortlistSize }}</td><td>{{ item.candidateCount }}</td><td>{{ item.decisionCode }}</td></tr></tbody></table></section>
  </div>
</template>

<style scoped>
.selection-page{display:grid;gap:18px}.selection-hero{height:auto;background:linear-gradient(135deg,#102a46,#101d30);border:1px solid #2b4c6d;border-radius:12px;padding:24px;display:flex;justify-content:space-between;align-items:center}.selection-hero p{color:#63b7ff;font-size:12px;letter-spacing:.12em}.selection-hero h1{font-size:30px;margin:4px 0}.selection-hero span,.boundary{color:#94a5bb}.selection-action{display:flex;gap:10px}.selection-action select{background:#0c1a2a;border:1px solid #34506d;color:#d9e6f5;border-radius:8px;padding:0 14px}.select-now{background:#1d8cff;border:0;color:white;border-radius:8px;font-size:16px;font-weight:700;padding:14px 26px;cursor:pointer}.select-now:disabled{opacity:.6}.boundary{font-size:12px;background:#121e2e;border-left:3px solid #e0a84e;padding:10px 14px}.diagnostic{font-size:12px;color:#7f91a8;background:#101b2a;border:1px solid #263951;border-radius:6px;padding:9px 12px}.diagnostic code{display:block;color:#9db0c8;margin-top:8px}.stage-track{display:grid;grid-template-columns:repeat(6,1fr);gap:8px}.stage-track div{display:flex;align-items:center;gap:8px;color:#65778f;background:#101b2a;padding:12px}.stage-track i{font-style:normal;border:1px solid #3c4d63;width:24px;height:24px;border-radius:50%;display:grid;place-items:center}.stage-track .active{color:#dcecff}.stage-track .active i{background:#1d8cff;border-color:#1d8cff}.summary-strip{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.summary-strip article,.result-panel,.result-grid article,.history-panel,.agent-panel{background:#0f1b2c;border:1px solid #263951;border-radius:10px;padding:18px}.summary-strip span{display:block;color:#7f91a8;font-size:12px}.summary-strip b{display:block;margin-top:8px}.result-panel h2,.result-grid h2,.history-panel h2,.agent-panel h2{margin:0 0 14px}.empty-result{display:grid;text-align:center;padding:36px;color:#8fa2ba}.empty-result strong{font-size:22px;color:#e2eaf5}.candidate-card{display:grid;grid-template-columns:54px 1fr;gap:14px;padding:16px 0;border-top:1px solid #25364c}.candidate-rank{height:42px;width:42px;border-radius:8px;background:#183b60;color:#73bcff;display:grid;place-items:center;font-size:20px;font-weight:700}.candidate-main h3{margin:0}.candidate-main small,.candidate-main p{color:#8ea0b7}.candidate-main p{margin:9px 0}.candidate-tags{display:flex;gap:8px;margin:10px 0;flex-wrap:wrap}.candidate-tags>*{background:#152a43;border-radius:4px;padding:4px 8px;font-size:12px}.candidate-tags b{color:#6bb8ff}.strategy-row{display:flex;gap:8px;overflow:auto;padding:5px 0 9px}.strategy-row span{min-width:225px;background:#111f31;border:1px solid #263a52;border-radius:6px;padding:8px;color:#8ea0b7;font-size:11px}.strategy-row b{display:block;color:#cfe3fa;margin-bottom:4px}.opposing{color:#c5a76e!important}.result-grid{display:grid;grid-template-columns:2fr 1fr;gap:12px}.result-grid p{color:#91a2b8}.result-grid a{color:#5eb1ff}.agent-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.agent-grid article{background:#111f31;border:1px solid #263a52;border-radius:7px;padding:12px}.agent-grid header{display:flex;justify-content:space-between;color:#dcecff}.agent-grid header span,.agent-grid small{color:#70839a;font-size:11px}.agent-grid p{font-size:12px;color:#9aacc1}.agent-grid em{font-style:normal;color:#5db2ff;margin-right:7px}.critic-box,.paper-box{margin-top:12px;display:flex;gap:12px;align-items:center;background:#16263a;padding:12px;border-radius:6px}.critic-box span,.paper-box span{color:#a6b5c7;flex:1}.critic-box small{color:#7589a0}.history-row{cursor:pointer}.history-row:hover{background:#14253a}table{width:100%;border-collapse:collapse;font-size:12px}th{text-align:left;color:#71839b;padding:8px;border-bottom:1px solid #2a3a50}td{padding:10px 8px;border-bottom:1px solid #1d2c40}td small{display:block;color:#64778f;margin-top:2px}@media(max-width:1100px){.selection-hero{align-items:flex-start;gap:16px}.summary-strip,.result-grid,.agent-grid{grid-template-columns:1fr}.stage-track{grid-template-columns:repeat(3,1fr)}}
</style>
