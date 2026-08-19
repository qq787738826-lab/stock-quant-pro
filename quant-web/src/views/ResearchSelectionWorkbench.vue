<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AgentConclusionPanel from '../components/AgentConclusionPanel.vue'
import {
  getResearchUniverse,
  getSelectionHistory,
  getLatestSelection,
  getSelectionMembers,
  getSelectionRun,
  startSelection,
} from '../research-selection/api'
import type {
  Candidate,
  SelectionResult,
  SelectionSummary,
  UniverseMemberPage,
  UniverseView,
} from '../research-selection/types'
import {
  displayHistoricalGrade,
  displayHistoricalLabel,
  displayHistoricalWindow,
  displayHistoryStatus,
  historicalBySecurity,
  securityKey,
} from '../research-selection/historical'
import {
  displayDecision,
  displayRisk,
  displayProduct,
  displayResearchList,
  displayStatus,
  displayStrategy,
  displayUniverseExclusion,
  displayValue,
  formatCurrency,
  formatDateTime,
  isLegacyResearchText,
} from '../localization/display'

const result = ref<SelectionResult | null>(null)
const history = ref<SelectionSummary[]>([])
const universe = ref<UniverseView | null>(null)
const memberPage = ref<UniverseMemberPage | null>(null)
const memberLoading = ref(false)
const memberEligibility = ref('')
const running = ref(false)
const error = ref('')
const diagnosticReason = ref('')
const universeLoading = ref(false)
const universeLoadError = ref('')
const historyLoadError = ref('')
const resultLoadError = ref('')
const selectedWindow = ref(20)
const activeRunId = ref<number | null>(null)
const route = useRoute()
const router = useRouter()
let timer: number | undefined
const stages = ['准备数据', '量化扫描', '策略分析', '智能体研究', '批判审查', '完成']
const currentStage = computed(() => {
  const value = history.value.find(item => item.runId === activeRunId.value)
    ?.status || ''
  return ({ QUEUED: 0, PREPARING_DATA: 0, QUANTITATIVE_SCAN: 1,
    STRATEGY_ANALYSIS: 2, AI_RESEARCH: 3, CRITIC_REVIEW: 4,
    COMPLETED: 5 } as Record<string, number>)[value] ?? 0
})
const legacyReport = computed(() => result.value?.agentReport.agentRuns.some(agent =>
  agent.findings.some(finding => isLegacyResearchText(finding.statement))) ?? false)
const stabilityMap = computed(() => historicalBySecurity(
  result.value?.historicalResearch))
const historyFor = (candidate: Candidate) => stabilityMap.value.get(
  securityKey(candidate.security))
const gradeClass = (grade?: string) => `history-grade grade-${grade || 'C'}`
const universeSize = computed(() => result.value?.universeFunnel?.memberCount
  ?? universe.value?.snapshot?.memberCount ?? 0)
const selectionBlocked = computed(() => universe.value != null
  && !universe.value.backfillPlan.executableWithinBudget)
const exclusionEntries = computed(() => Object.entries(
  result.value?.universeFunnel?.exclusionReasonCounts ?? {})
  .sort((left, right) => right[1] - left[1]))

async function loadUniversePanel() {
  universeLoading.value = true
  universeLoadError.value = ''
  try {
    universe.value = await getResearchUniverse()
  } catch (cause) {
    universeLoadError.value = loadFriendly('UNIVERSE', cause)
  } finally {
    universeLoading.value = false
  }
}
async function loadHistoryAndResult() {
  historyLoadError.value = ''
  resultLoadError.value = ''
  try {
    history.value = await getSelectionHistory()
  } catch (cause) {
    historyLoadError.value = loadFriendly('HISTORY', cause)
  }
  try {
    const requestedId = Number(route.query.run)
    let latest: SelectionSummary | SelectionResult | undefined
    if (Number.isSafeInteger(requestedId) && requestedId > 0) {
      latest = await getSelectionRun(requestedId)
    } else {
      const newest = history.value[0]
      latest = newest && newest.status !== 'FAILED'
        ? newest
        : history.value.find(item => item.status === 'COMPLETED')
      if (!latest) latest = await getLatestSelection()
    }
    if (latest?.status === 'COMPLETED') {
      const completed = 'contractVersion' in latest
        ? latest : await getSelectionRun(latest.runId)
      if ('contractVersion' in completed) {
        result.value = completed
        await loadMembers(completed.runId)
      }
    } else if (latest && latest.status !== 'FAILED') {
      running.value = true
      activeRunId.value = latest.runId
      poll(latest.runId)
    }
  } catch (cause) {
    resultLoadError.value = loadFriendly('RESULT', cause)
  }
}
async function load() {
  await Promise.allSettled([loadHistoryAndResult(), loadUniversePanel()])
}
async function openRun(id: number) {
  resultLoadError.value = ''
  try {
    const value = await getSelectionRun(id)
    if ('contractVersion' in value && value.status === 'COMPLETED') {
      result.value = value
      await loadMembers(id)
    }
  } catch (cause) {
    resultLoadError.value = loadFriendly('RESULT', cause)
  }
}
async function loadMembers(id: number, page = 0) {
  if (!result.value?.universeFunnel) {
    memberPage.value = null
    return
  }
  memberLoading.value = true
  try {
    memberPage.value = await getSelectionMembers(
      id, page, 50, memberEligibility.value)
  } finally {
    memberLoading.value = false
  }
}
function changeMemberFilter() {
  if (result.value) void loadMembers(result.value.runId, 0)
}
async function start() {
  running.value = true; error.value = ''; diagnosticReason.value = ''
  result.value = null; memberPage.value = null
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
    if (isRequestTimeout(cause)) await loadHistoryAndResult()
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
        await loadMembers(id)
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
  if (reason === 'M4_SHADOW_REQUEST_INVALID'
    || reason === 'RESEARCH_SELECTION_PAPER_EXECUTION_NOT_AFTER_AS_OF') {
    return '研究时间边界校验未通过；系统没有使用尚未结束的当日日线。'
  }
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
  if (isRequestTimeout(cause)) {
    return '全主板数据核验响应超时；系统可能已经接收任务，正在从历史记录核对，请勿重复提交。'
  }
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
  return '研究请求未能确认；请查看历史结果或等待系统恢复，系统不会自动重复创建任务。'
}
function isRequestTimeout(cause: unknown) {
  const message = cause instanceof Error ? cause.message : String(cause || '')
  return /timeout|ECONNABORTED/i.test(message)
}
function loadFriendly(scope: 'UNIVERSE' | 'HISTORY' | 'RESULT', cause: unknown) {
  const timeout = isRequestTimeout(cause)
  if (scope === 'UNIVERSE') {
    return timeout
      ? '全主板股票池核验超时；历史选股结果仍可独立查看，系统未创建新任务。'
      : '全主板股票池暂时无法加载；历史选股结果仍可独立查看。'
  }
  if (scope === 'HISTORY') {
    return timeout
      ? '历史选股结果加载超时；股票池和指定结果仍可独立查看。'
      : '历史选股结果暂时无法加载；股票池信息不受影响。'
  }
  return timeout
    ? '选股结果详情加载超时；请稍后重新打开该历史结果。'
    : '选股结果详情暂时无法加载；历史记录没有被修改。'
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
      <div><p>全主板研究股票池 V1 · 当前时点研究</p><h1>立即选股</h1>
        <span>确定性扫描 {{ universeSize || '动态' }} 只沪深主板股票，分层筛选后前10名进入七智能体深度研究。</span></div>
      <div class="selection-action"><select v-model="selectedWindow" :disabled="running">
        <option :value="20">最近20交易日</option><option :value="60">最近60交易日</option>
        <option :value="120">最近120交易日</option><option :value="250">最近250交易日</option>
      </select><button class="select-now" :disabled="running || selectionBlocked" @click="start">{{ running ? '研究进行中…' : selectionBlocked ? '等待数据预算' : '立即选股' }}</button></div>
    </header>
    <div class="boundary">仅限研究与模拟 · 真实交易已关闭 · 当前分析不是历史实时影子记录</div>
    <el-alert v-if="universeLoading" title="正在核验全主板股票池，本页其他历史结果会先行加载。"
      type="info" :closable="false" show-icon />
    <el-alert v-if="universeLoadError" :title="universeLoadError" type="warning" :closable="false" show-icon />
    <el-alert v-if="historyLoadError" :title="historyLoadError" type="warning" :closable="false" show-icon />
    <el-alert v-if="resultLoadError" :title="resultLoadError" type="warning" :closable="false" show-icon />
    <section v-if="universe?.snapshot" class="universe-snapshot">
      <header><div><h2>全主板股票池快照</h2><p>{{ universe.snapshot.snapshotId }} · 生效日 {{ universe.snapshot.effectiveDate }}</p></div><b>{{ universe.snapshot.memberCount }} 只</b></header>
      <div class="universe-facts">
        <span>沪市 {{ universe.snapshot.sseCount }}</span><span>深市 {{ universe.snapshot.szseCount }}</span>
        <span>ST / *ST {{ universe.snapshot.stCount }}</span><span>来源 {{ universe.snapshot.source }}</span>
      </div>
    </section>
    <el-alert v-if="universe && !universe.backfillPlan.executableWithinBudget"
      title="全主板历史数据尚未完成，系统已按预算门禁停止，不会进行部分回填。"
      type="warning" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <details v-if="error && diagnosticReason" class="diagnostic"><summary>高级诊断信息</summary><code>{{ diagnosticReason }}</code></details>
    <div v-if="running" class="stage-track"><div v-for="(stage, index) in stages" :key="stage" :class="{ active: index <= currentStage }"><i>{{ index + 1 }}</i><span>{{ stage }}</span></div></div>

    <template v-if="result">
      <el-alert v-if="legacyReport" title="历史原始报告：原始英文智能体正文保持不可变；新研究默认使用简体中文。" type="info" :closable="false" show-icon />
      <div class="anchor-notice">本次研究使用截至 <strong>{{ result.anchorTradeDate }}</strong> 收盘的数据。</div>
      <section class="summary-strip"><article><span>分析窗口</span><b>{{ result.dataCoverage.rangeStart }} → {{ result.dataCoverage.rangeEnd }}</b></article><article><span>完整股票</span><b>{{ result.dataCoverage.completeSecurityCount }} / {{ result.dataCoverage.securityCount }}</b></article><article><span>总耗时</span><b>{{ (result.timings.totalMillis / 1000).toFixed(1) }} 秒</b></article><article><span>本次成本</span><b>{{ formatCurrency(result.usage.conservativeCostCny, 4) }}</b></article></section>
      <section v-if="result.universeFunnel" class="universe-funnel">
        <header><div><h2>全主板分层扫描</h2><p>{{ result.universeFunnel.snapshotId }} · {{ displayProduct(result.universeFunnel.universeVersion) }}</p></div><b>{{ result.universeFunnel.memberCount }} 只</b></header>
        <div class="funnel-grid">
          <article><span>数据合格</span><b>{{ result.universeFunnel.eligibleCount }}</b></article>
          <article><span>明确排除</span><b>{{ result.universeFunnel.excludedCount }}</b></article>
          <article><span>基础扫描</span><b>{{ result.universeFunnel.basicScannedCount }}</b></article>
          <article><span>历史 Top200</span><b>{{ result.universeFunnel.historicalScoredCount }}</b></article>
          <article><span>策略 Top30</span><b>{{ result.universeFunnel.strategyComparedCount }}</b></article>
          <article><span>智能体 Top10</span><b>{{ result.universeFunnel.agentResearchedCount }}</b></article>
          <article><span>最终候选</span><b>{{ result.universeFunnel.candidateCount }}</b></article>
        </div>
        <div class="exclusion-reasons"><span v-for="entry in exclusionEntries" :key="entry[0]">{{ displayUniverseExclusion(entry[0]) }} {{ entry[1] }}</span></div>
      </section>
      <section v-if="result.historicalResearch" class="historical-overview">
        <header>
          <div><h2>历史稳定性研究</h2><p>
            {{ displayHistoricalLabel(result.historicalResearch.researchLabel) }} ·
            {{ displayHistoricalLabel(result.historicalResearch.pitQualification) }} ·
            不代表历史实时影子业绩
          </p></div>
          <div class="grade-distribution">
            <b>A {{ result.historicalResearch.gradeDistribution.A }}</b>
            <b>B {{ result.historicalResearch.gradeDistribution.B }}</b>
            <b>C {{ result.historicalResearch.gradeDistribution.C }}</b>
          </div>
        </header>
        <div class="coverage-grid">
          <article v-for="coverage in result.historicalResearch.windowCoverage" :key="coverage.requestedSessions" :class="{ insufficient: coverage.status !== 'AVAILABLE' }">
            <span>{{ coverage.requestedSessions }}日</span>
            <strong>{{ displayHistoryStatus(coverage) }}</strong>
            <small>{{ coverage.rangeStart }} → {{ coverage.rangeEnd }}</small>
          </article>
        </div>
        <p class="historical-boundary">计算版本 {{ result.historicalResearch.version }}；knownAt与防未来检查{{ result.historicalResearch.noFutureDataLeakage ? '通过' : '未通过' }}。历史不足只降低等级，不会使立即选股失败，也不会触发历史补采。</p>
      </section>
      <section v-else class="historical-overview historical-legacy">
        <h2>历史稳定性研究</h2><p>该结果生成于V1.0.8之前，未保存历史稳定性投影；原始报告保持不变。</p>
      </section>
      <section class="result-panel"><h2>今日选股结果</h2>
        <div v-if="result.emptyResult" class="empty-result"><strong>今日无合格候选</strong><span>{{ displayDecision('INSUFFICIENT_EVIDENCE') }} · 系统没有为了展示效果强制推荐股票。</span></div>
        <article v-for="candidate in result.candidates" :key="candidate.security.symbol" class="candidate-card">
          <div class="candidate-rank">{{ candidate.rank }}</div>
          <div class="candidate-main">
            <h3>{{ candidate.name }} <small>{{ candidate.security.symbol }} / {{ candidate.security.exchange }}</small></h3>
            <div class="candidate-tags">
              <b>当前排名分 {{ candidate.quantitativeScore }}</b>
              <span v-if="historyFor(candidate)" :class="gradeClass(historyFor(candidate)?.grade)">
                历史 {{ historyFor(candidate)?.score }} · {{ displayHistoricalGrade(historyFor(candidate)?.grade) }}
              </span>
              <span>风险 {{ displayRisk(candidate.riskLevel) }}</span>
              <span>置信度 {{ pct(candidate.confidence) }}</span>
              <span>{{ displayDecision(candidate.recommendation) }}</span>
            </div>
            <div class="candidate-evidence-grid">
              <section><h4>当前研究</h4>
                <p><strong>量化：</strong>{{ displayResearchList(candidate.supportingReasons) }}</p>
                <p><strong>智能体：</strong>{{ displayDecision(result.agentReport.finalDecision.code) }} · {{ result.agentReport.finalDecision.supportingEvidenceIds.length }} 条支持证据</p>
                <p><strong>策略：</strong>{{ displayStrategy(candidate.preferredStrategy) }} · 最大回撤 {{ pct(candidate.maxDrawdown) }} · {{ displayValue(candidate.trend) }}</p>
              </section>
              <section><h4>历史稳定性</h4>
                <template v-if="historyFor(candidate)">
                  <p><strong>可用历史：</strong>{{ historyFor(candidate)?.availableSessions }} 个交易日</p>
                  <p><strong>最差窗口：</strong>{{ displayHistoricalWindow(historyFor(candidate)?.worstWindow) }} · {{ pct(historyFor(candidate)?.worstWindowReturn || 0) }}</p>
                  <p><strong>Walk-forward：</strong>{{ historyFor(candidate)?.walkForward.available ? `训练${historyFor(candidate)?.walkForward.trainSessions}日/测试${historyFor(candidate)?.walkForward.testSessions}日，${historyFor(candidate)?.walkForward.foldCount}折，样本外平均 ${pct(historyFor(candidate)?.walkForward.averageOutOfSampleReturn || 0)}` : '历史覆盖不足' }}</p>
                  <p><strong>支持：</strong>{{ displayResearchList(historyFor(candidate)?.supportingEvidence || []) }}</p>
                  <p class="opposing"><strong>限制：</strong>{{ displayResearchList(historyFor(candidate)?.limitations || []) }}</p>
                </template>
                <p v-else>历史覆盖不足（INSUFFICIENT_HISTORY）</p>
              </section>
              <section><h4>Live Shadow验证</h4>
                <template v-if="historyFor(candidate)">
                  <p><strong>前瞻样本：</strong>{{ historyFor(candidate)?.liveShadowSamples }} 次</p>
                  <p>{{ (historyFor(candidate)?.liveShadowSamples || 0) > 0 ? '已有冻结Live Shadow样本，仅作辅助验证。' : '样本不足；历史研究不得替代Live Shadow。' }}</p>
                </template>
                <p v-else>尚无可关联样本。</p>
              </section>
            </div>
            <div class="strategy-row"><span v-for="strategy in candidate.strategyComparison" :key="strategy.strategyCode"><b>{{ displayStrategy(strategy.strategyCode) }}</b> 收益 {{ pct(strategy.totalReturn) }} · 夏普比率 {{ Number(strategy.sharpeRatio).toFixed(2) }} · 回撤 {{ pct(strategy.maxDrawdown) }}</span></div>
            <p><strong>批判审查：</strong>{{ displayResearchList(candidate.criticIssues) || '未发现阻断性问题' }}</p>
            <p class="opposing"><strong>当前研究限制：</strong>{{ displayResearchList(candidate.opposingReasons) || '无额外限制' }}</p>
          </div>
        </article>
      </section>
      <section class="result-grid"><article><h2>量化前10名</h2><table><thead><tr><th>#</th><th>股票</th><th>行业</th><th>当前分</th><th>历史分</th><th>等级</th><th>20日</th><th>60日</th><th>回撤</th></tr></thead><tbody><tr v-for="score in result.shortlist" :key="score.security.symbol"><td>{{ score.rank }}</td><td>{{ score.name }}<small>{{ score.security.symbol }}</small></td><td>{{ score.industry }}</td><td>{{ score.score }}</td><td>{{ stabilityMap.get(securityKey(score.security))?.score ?? '—' }}</td><td>{{ stabilityMap.get(securityKey(score.security))?.grade ?? '—' }}</td><td>{{ pct(score.twentyDayReturn) }}</td><td>{{ pct(score.sixtyDayReturn) }}</td><td>{{ pct(score.maxDrawdown) }}</td></tr></tbody></table></article><article><h2>研究血缘与用量</h2><p>股票池版本：{{ displayProduct(result.lineage.researchUniverseVersion) }}</p><p>排名版本：{{ result.lineage.rankingVersion }}</p><p>历史稳定性：{{ result.lineage.historicalStabilityVersion || '旧结果未生成' }}</p><p>模型：{{ result.lineage.modelProvider }} / {{ result.lineage.model }}</p><p>智能体：{{ result.usage.modelCalls }} 次调用 / {{ result.usage.totalTokens }} 个令牌</p><p>Tushare：{{ result.usage.tushareProviderRequests }} 次请求 / 重试 {{ result.usage.retryCount }}</p><p>时点边界：{{ result.dataCoverage.noFutureDataLeakage ? '通过' : '未通过' }}</p><router-link to="/shadow-research">查看冻结研究与模拟账本 →</router-link></article></section>
      <AgentConclusionPanel
        :report="result.agentReport"
        :paper-enabled="result.paperEnabled"
        :shadow-run-id="result.shadowRunId"
      />
      <section v-if="result.universeFunnel" class="member-panel" v-loading="memberLoading">
        <header><div><h2>股票池资格明细</h2><p>按页加载，不会向浏览器一次返回全部主板证券。</p></div>
          <select v-model="memberEligibility" @change="changeMemberFilter">
            <option value="">全部</option><option value="ELIGIBLE">数据合格</option><option value="EXCLUDED">已排除</option>
          </select>
        </header>
        <table><thead><tr><th>证券</th><th>交易所</th><th>行业</th><th>资格</th><th>原因</th><th>历史</th><th>基础排名</th><th>历史排名</th><th>策略排名</th></tr></thead>
          <tbody><tr v-for="item in memberPage?.members || []" :key="item.member.tsCode">
            <td>{{ item.member.name }}<small>{{ item.member.tsCode }}</small></td><td>{{ item.member.exchange }}</td><td>{{ item.member.industry || '未分类' }}</td>
            <td>{{ item.status === 'ELIGIBLE' ? '数据合格' : '已排除' }}</td>
            <td>{{ item.exclusionReasons.length ? item.exclusionReasons.map(displayUniverseExclusion).join('、') : '—' }}</td>
            <td>{{ item.availableSessions }} 日</td><td>{{ item.basicRank || '—' }}</td><td>{{ item.historicalRank || '—' }}</td><td>{{ item.strategyRank || '—' }}</td>
          </tr></tbody>
        </table>
        <el-pagination v-if="memberPage && memberPage.total > memberPage.size" background layout="prev, pager, next"
          :page-size="memberPage.size" :total="memberPage.total" :current-page="memberPage.page + 1"
          @current-change="(page: number) => loadMembers(result!.runId, page - 1)" />
      </section>
    </template>

    <section class="history-panel"><h2>历史选股结果</h2><table><thead><tr><th>时间</th><th>锚点</th><th>状态</th><th>股票池</th><th>前10名</th><th>候选</th><th>结论</th></tr></thead><tbody><tr v-for="item in history" :key="item.runId" class="history-row" @click="openRun(item.runId)"><td>{{ formatDateTime(item.createdAt) }}</td><td>{{ item.anchorTradeDate || '准备中' }}</td><td>{{ displayStatus(item.status) }}</td><td>{{ item.universeSize }}</td><td>{{ item.shortlistSize }}</td><td>{{ item.candidateCount }}</td><td>{{ displayDecision(item.decisionCode) }}</td></tr></tbody></table></section>
  </div>
</template>

<style scoped>
.selection-page{display:grid;gap:18px}.selection-hero{height:auto;background:linear-gradient(135deg,#102a46,#101d30);border:1px solid #2b4c6d;border-radius:12px;padding:24px;display:flex;justify-content:space-between;align-items:center}.selection-hero p{color:#63b7ff;font-size:12px;letter-spacing:.12em}.selection-hero h1{font-size:30px;margin:4px 0}.selection-hero span,.boundary{color:#94a5bb}.selection-action{display:flex;gap:10px}.selection-action select{background:#0c1a2a;border:1px solid #34506d;color:#d9e6f5;border-radius:8px;padding:0 14px}.select-now{background:#1d8cff;border:0;color:white;border-radius:8px;font-size:16px;font-weight:700;padding:14px 26px;cursor:pointer}.select-now:disabled{opacity:.6}.boundary{font-size:12px;background:#121e2e;border-left:3px solid #e0a84e;padding:10px 14px}.anchor-notice{font-size:13px;color:#a9c6e5;background:#10243a;border-left:3px solid #4ca4f5;padding:11px 14px}.anchor-notice strong{color:#e4f1ff}.diagnostic{font-size:12px;color:#7f91a8;background:#101b2a;border:1px solid #263951;border-radius:6px;padding:9px 12px}.diagnostic code{display:block;color:#9db0c8;margin-top:8px}.stage-track{display:grid;grid-template-columns:repeat(6,1fr);gap:8px}.stage-track div{display:flex;align-items:center;gap:8px;color:#65778f;background:#101b2a;padding:12px}.stage-track i{font-style:normal;border:1px solid #3c4d63;width:24px;height:24px;border-radius:50%;display:grid;place-items:center}.stage-track .active{color:#dcecff}.stage-track .active i{background:#1d8cff;border-color:#1d8cff}.summary-strip{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}.summary-strip article,.result-panel,.result-grid article,.history-panel,.agent-panel{background:#0f1b2c;border:1px solid #263951;border-radius:10px;padding:18px}.summary-strip span{display:block;color:#7f91a8;font-size:12px}.summary-strip b{display:block;margin-top:8px}.result-panel h2,.result-grid h2,.history-panel h2,.agent-panel h2{margin:0 0 14px}.empty-result{display:grid;text-align:center;padding:36px;color:#8fa2ba}.empty-result strong{font-size:22px;color:#e2eaf5}.candidate-card{display:grid;grid-template-columns:54px 1fr;gap:14px;padding:16px 0;border-top:1px solid #25364c}.candidate-rank{height:42px;width:42px;border-radius:8px;background:#183b60;color:#73bcff;display:grid;place-items:center;font-size:20px;font-weight:700}.candidate-main h3{margin:0}.candidate-main small,.candidate-main p{color:#8ea0b7}.candidate-main p{margin:9px 0}.candidate-tags{display:flex;gap:8px;margin:10px 0;flex-wrap:wrap}.candidate-tags>*{background:#152a43;border-radius:4px;padding:4px 8px;font-size:12px}.candidate-tags b{color:#6bb8ff}.strategy-row{display:flex;gap:8px;overflow:auto;padding:5px 0 9px}.strategy-row span{min-width:225px;background:#111f31;border:1px solid #263a52;border-radius:6px;padding:8px;color:#8ea0b7;font-size:11px}.strategy-row b{display:block;color:#cfe3fa;margin-bottom:4px}.opposing{color:#c5a76e!important}.result-grid{display:grid;grid-template-columns:2fr 1fr;gap:12px}.result-grid p{color:#91a2b8}.result-grid a{color:#5eb1ff}.agent-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.agent-grid article{background:#111f31;border:1px solid #263a52;border-radius:7px;padding:12px}.agent-grid header{display:flex;justify-content:space-between;color:#dcecff}.agent-grid header span,.agent-grid small{color:#70839a;font-size:11px}.agent-grid p{font-size:12px;color:#9aacc1}.agent-grid em{font-style:normal;color:#5db2ff;margin-right:7px}.critic-box,.paper-box{margin-top:12px;display:flex;gap:12px;align-items:center;background:#16263a;padding:12px;border-radius:6px}.critic-box span,.paper-box span{color:#a6b5c7;flex:1}.critic-box small{color:#7589a0}.history-row{cursor:pointer}.history-row:hover{background:#14253a}table{width:100%;border-collapse:collapse;font-size:12px}th{text-align:left;color:#71839b;padding:8px;border-bottom:1px solid #2a3a50}td{padding:10px 8px;border-bottom:1px solid #1d2c40}td small{display:block;color:#64778f;margin-top:2px}@media(max-width:1100px){.selection-hero{align-items:flex-start;gap:16px}.summary-strip,.result-grid,.agent-grid{grid-template-columns:1fr}.stage-track{grid-template-columns:repeat(3,1fr)}}
.historical-overview{background:#0f1b2c;border:1px solid #2d4966;border-radius:10px;padding:18px}.historical-overview header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.historical-overview h2{margin:0 0 6px}.historical-overview p{margin:0;color:#8fa4bb;font-size:12px}.grade-distribution{display:flex;gap:8px}.grade-distribution b{padding:6px 10px;border-radius:5px;background:#182a3d;color:#b9d1e9}.coverage-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:9px;margin-top:14px}.coverage-grid article{background:#13263a;border:1px solid #2b4d6c;border-radius:7px;padding:10px}.coverage-grid article.insufficient{border-color:#66553b;background:#272318}.coverage-grid span,.coverage-grid strong,.coverage-grid small{display:block}.coverage-grid span{color:#71b9fb}.coverage-grid strong{margin:5px 0;color:#d8e7f6;font-size:12px}.coverage-grid small{color:#71849a}.historical-boundary{margin-top:12px!important;padding-top:10px;border-top:1px solid #20374d}.historical-legacy{border-color:#5d523d}.candidate-evidence-grid{display:grid;grid-template-columns:1fr 1.25fr .75fr;gap:10px;margin:12px 0}.candidate-evidence-grid section{background:#101f31;border:1px solid #263a52;border-radius:7px;padding:11px}.candidate-evidence-grid h4{margin:0 0 8px;color:#cfe3f7}.history-grade{font-weight:700}.grade-A{color:#67d5a0}.grade-B{color:#77bfff}.grade-C{color:#e0ae62}@media(max-width:1100px){.coverage-grid,.candidate-evidence-grid{grid-template-columns:1fr}.historical-overview header{display:block}.grade-distribution{margin-top:10px}}
.universe-snapshot,.universe-funnel,.member-panel{background:#0f1b2c;border:1px solid #2d4966;border-radius:10px;padding:18px}.universe-snapshot header,.universe-funnel header,.member-panel header{display:flex;justify-content:space-between;align-items:flex-start;gap:18px}.universe-snapshot h2,.universe-funnel h2,.member-panel h2{margin:0 0 5px}.universe-snapshot p,.universe-funnel p,.member-panel p{margin:0;color:#7f93aa;font-size:12px}.universe-snapshot header>b,.universe-funnel header>b{font-size:24px;color:#64bbff}.universe-facts,.exclusion-reasons{display:flex;flex-wrap:wrap;gap:8px;margin-top:13px}.universe-facts span,.exclusion-reasons span{padding:6px 9px;background:#13263a;border:1px solid #29445f;border-radius:5px;color:#9fb4ca;font-size:12px}.funnel-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:8px;margin-top:14px}.funnel-grid article{padding:10px;background:#12243a;border:1px solid #29435f;border-radius:6px}.funnel-grid span,.funnel-grid b{display:block}.funnel-grid span{color:#7f93aa;font-size:11px}.funnel-grid b{margin-top:6px;color:#d8e9fa;font-size:18px}.member-panel header select{background:#0c1a2a;border:1px solid #34506d;color:#d9e6f5;border-radius:7px;padding:8px 12px}.member-panel table{margin:12px 0}.member-panel .el-pagination{justify-content:flex-end}@media(max-width:1100px){.funnel-grid{grid-template-columns:repeat(2,1fr)}.universe-snapshot header,.universe-funnel header,.member-panel header{display:block}.member-panel header select{margin-top:10px}}
</style>
