<script setup lang="ts">
import { computed } from 'vue'
import { AGENT_NAMES, FINAL_DECISION_NAMES } from '../../agent-team/presentation'
import {
  backtestDisplayState,
  displayTime,
  displayValue,
  extractReasonCodes,
  runErrors,
  runFindings,
} from '../../research-preview/presentation'
import type {
  PreviewIssue,
  PreviewMode,
  PreviewTaskBundle,
} from '../../research-preview/types'

const props = defineProps<{
  bundle: PreviewTaskBundle | null
  mode: PreviewMode
  issues: PreviewIssue[]
}>()

const task = computed(() => props.bundle?.task ?? null)
const decision = computed(() => props.bundle?.decision ?? null)
const backtest = computed(() =>
  props.bundle?.runs.find((run) => run.agentCode === 'STRATEGY_BACKTEST') ?? null,
)
const announcement = computed(() =>
  props.bundle?.runs.find((run) => run.agentCode === 'ANNOUNCEMENT_RISK') ?? null,
)
const position = computed(() =>
  props.bundle?.runs.find((run) => run.agentCode === 'POSITION_RISK') ?? null,
)
const reasons = computed(() => extractReasonCodes(props.bundle, props.issues))
const backtestState = computed(() => backtestDisplayState(props.bundle))

function outputJson(value: unknown): string {
  return value == null ? '暂无' : JSON.stringify(value, null, 2)
}
</script>

<template>
  <section class="outcome-panel">
    <div class="panel-heading">
      <div><p class="eyebrow">SINGLE SECURITY WORKBENCH</p><h2>单股分析工作台</h2></div>
      <span>{{ bundle?.qualification ?? '暂无数据资格' }}</span>
    </div>
    <div v-if="!bundle" class="empty">
      请选择已有候选或Agent历史任务。页面不会创建、刷新、重试或重新分析任务。
    </div>
    <template v-else>
      <div class="mode-ribbon">
        <b>{{ mode }}</b>
        <span>{{ bundle.synthetic ? 'SYNTHETIC · NOT_REAL_MARKET_RESULT' : 'READ_ONLY · EXISTING_RESULT' }}</span>
      </div>
      <div class="task-facts">
        <div><span>股票代码</span><strong>{{ task?.symbol ?? '暂无' }}</strong></div>
        <div><span>交易日期</span><strong>{{ task?.tradeDate ?? '暂无' }}</strong></div>
        <div><span>taskId</span><strong>{{ task?.id ?? '暂无' }}</strong></div>
        <div><span>ruleVersion</span><strong>{{ task?.ruleVersion ?? '暂无' }}</strong></div>
        <div><span>contextSchemaVersion</span><strong>{{ task?.contextSchemaVersion ?? '暂无' }}</strong></div>
        <div><span>contextHash</span><strong class="hash">{{ task?.contextHash ?? '暂无' }}</strong></div>
        <div><span>triggerType</span><strong>{{ task?.triggerType ?? '暂无' }}</strong></div>
        <div><span>executionMode</span><strong>{{ task?.executionMode ?? '暂无' }}</strong></div>
        <div><span>任务状态</span><strong>{{ task?.status ?? '暂无' }}</strong></div>
        <div><span>创建时间</span><strong>{{ displayTime(task?.createdAt) }}</strong></div>
        <div><span>开始时间</span><strong>{{ displayTime(task?.startedAt) }}</strong></div>
        <div><span>完成时间</span><strong>{{ displayTime(task?.finishedAt) }}</strong></div>
      </div>

      <section class="decision-card">
        <header>
          <div><p class="eyebrow">CHIEF DECISION</p><h3>总控综合结论</h3></div>
          <strong>{{ decision ? FINAL_DECISION_NAMES[decision.decision] : '暂无' }}</strong>
        </header>
        <div v-if="!decision" class="empty compact">总控结果尚未持久化，不显示中性或安全结论。</div>
        <template v-else>
          <div class="decision-code">{{ decision.decision }}</div>
          <div class="decision-facts">
            <div><span>gateStatus</span><b>{{ decision.gateStatus }}</b></div>
            <div><span>vetoed</span><b>{{ decision.vetoed ? '是' : '否' }}</b></div>
            <div><span>score</span><b>{{ displayValue(decision.score) }}</b></div>
            <div><span>confidence</span><b>{{ displayValue(decision.confidence) }}</b></div>
            <div><span>generatedAt</span><b>{{ displayTime(decision.generatedAt) }}</b></div>
            <div><span>contextHash</span><b class="hash">{{ decision.contextHash }}</b></div>
          </div>
          <p class="summary">{{ decision.summary }}</p>
          <p v-if="decision.decision === 'PASS_TO_MANUAL_REVIEW'" class="notice">
            PASS_TO_MANUAL_REVIEW仅表示进入人工研究复核，不表示买入、卖出或执行交易。
          </p>
          <p v-if="decision.decision === 'BLOCKED_BY_DATA_QUALITY'" class="notice warn">
            BLOCKED_BY_DATA_QUALITY是数据质量阻断，不是POSITION_RISK正式风险否决。
          </p>
          <p v-if="decision.decision === 'REJECTED_BY_VETO'" class="notice danger">
            REJECTED_BY_VETO必须由POSITION_RISK正式否决记录支持。
          </p>
          <p v-if="decision.decision === 'INSUFFICIENT_DATA'" class="notice warn">
            INSUFFICIENT_DATA表示数据不足，不得解释为中性、安全或可执行结论。
          </p>
          <dl class="id-list">
            <div><dt>sourceRunIds</dt><dd>{{ (decision.sourceRunIds ?? []).join(', ') || '暂无' }}</dd></div>
            <div><dt>vetoIds</dt><dd>{{ (decision.vetoIds ?? []).join(', ') || '暂无' }}</dd></div>
          </dl>
          <div v-if="(decision.findings ?? []).length" class="decision-findings">
            <article v-for="finding in decision.findings ?? []" :key="finding.findingId">
              <code>{{ finding.code }}</code><b>{{ finding.title }} · {{ finding.severity }}</b>
              <p>{{ finding.detail }}</p>
              <small>evidenceIds：{{ finding.evidenceIds.join(', ') || '暂无' }}</small>
            </article>
          </div>
        </template>
      </section>

      <div class="specialty-grid">
        <section class="specialty-card">
          <header><div><p class="eyebrow">BACKTEST</p><h3>策略回测区域</h3></div><code>{{ backtestState }}</code></header>
          <p>{{ backtest?.summary || '暂无已有STRATEGY_BACKTEST结果' }}</p>
          <article v-for="finding in runFindings(backtest)" :key="finding.findingId" class="finding">
            <code>{{ finding.code }}</code><b>{{ finding.title }}</b><span>{{ finding.detail }}</span>
            <small>evidenceIds：{{ finding.evidenceIds.join(', ') || '暂无' }}</small>
          </article>
          <div v-for="error in runErrors(backtest)" :key="error.code" class="reason">
            <code>{{ error.code }}</code><span>{{ error.message }}</span>
          </div>
          <details v-if="backtest?.outputJson != null">
            <summary>查看已有outputJson（只读）</summary>
            <pre>{{ outputJson(backtest.outputJson) }}</pre>
          </details>
          <p class="boundary">不启动新回测，不使用扫描旧回测替代，不从QFQ价格反推因子。</p>
        </section>

        <section class="specialty-card">
          <header><div><p class="eyebrow">ANNOUNCEMENT RISK</p><h3>公告风险</h3></div><code>{{ announcement?.status ?? '暂无' }}</code></header>
          <p>{{ announcement?.summary || '暂无已有公告风险结果' }}</p>
          <article v-for="finding in runFindings(announcement)" :key="finding.findingId" class="finding">
            <code>{{ finding.code }}</code><b>{{ finding.title }}</b><span>{{ finding.detail }}</span>
            <small>evidenceIds：{{ finding.evidenceIds.join(', ') || '暂无' }}</small>
          </article>
          <p class="boundary">仅展示已有证据中的研究级、DATE_ONLY等边界，不新增公告事实。</p>
        </section>

        <section class="specialty-card position-card">
          <header><div><p class="eyebrow">POSITION RISK</p><h3>持仓风险与正式veto</h3></div><code>{{ position?.status ?? '暂无' }}</code></header>
          <p>{{ position?.summary || '暂无已有仓位风险结果' }}</p>
          <article v-for="finding in runFindings(position)" :key="finding.findingId" class="finding">
            <code>{{ finding.code }}</code><b>{{ finding.title }}</b><span>{{ finding.detail }}</span>
            <small>evidenceIds：{{ finding.evidenceIds.join(', ') || '暂无' }}</small>
          </article>
          <div v-if="!bundle.vetoes.length" class="empty compact">无正式veto记录</div>
          <article v-for="veto in bundle.vetoes" v-else :key="veto.vetoId" class="veto">
            <div><code>{{ veto.vetoCode }}</code><b>{{ veto.vetoId }}</b></div>
            <p>{{ veto.reason }}</p>
            <small>agent={{ veto.agentCode }} · runId={{ veto.runId }} · evidenceIds={{ (veto.evidenceIds ?? []).join(', ') || '暂无' }}</small>
          </article>
          <p class="boundary">POSITION_RISK是唯一正式否决来源；页面不读取券商账户或输出仓位操作指令。</p>
        </section>
      </div>

      <section class="reason-section">
        <div class="panel-heading">
          <div><p class="eyebrow">STRUCTURED REASONS</p><h3>reasonCode与不可用原因</h3></div>
          <span>{{ reasons.length }}项</span>
        </div>
        <div v-if="!reasons.length" class="empty compact">暂无明确结构化reasonCode</div>
        <div class="reason-grid">
          <article v-for="reason in reasons" :key="`${reason.source}-${reason.agentCode}-${reason.code}`">
            <code>{{ reason.code }}</code>
            <b>{{ reason.source }}<template v-if="reason.agentCode"> · {{ AGENT_NAMES[reason.agentCode] }}</template></b>
            <p>{{ reason.detail || '暂无附加详情' }}</p>
          </article>
        </div>
        <p class="boundary">仅从finding.code、errors[].code、明确reasonCode、正式veto及task/run errorMessage提取，不从summary猜测。</p>
      </section>
    </template>
  </section>
</template>

<style scoped>
.outcome-panel { padding: 20px; border: 1px solid #223a57; border-radius: 12px; background: #0e1a2b; }.panel-heading, .decision-card header, .specialty-card header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.panel-heading h2, .panel-heading h3, h3 { margin: 0; }.eyebrow { margin: 0 0 5px; color: #5eaaff; font-size: 10px; font-weight: 800; letter-spacing: .15em; }
.panel-heading > span { color: #72d3ad; font: 10px ui-monospace, monospace; }.mode-ribbon { display: flex; gap: 10px; margin: 15px 0; padding: 9px 11px; border: 1px solid #285278; border-radius: 7px; color: #78c0ff; font-size: 10px; }.mode-ribbon span { padding-left: 10px; border-left: 1px solid #497291; }
.task-facts, .decision-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }.task-facts div, .decision-facts div { min-width: 0; padding: 9px 10px; border: 1px solid #1f334b; border-radius: 7px; background: #091422; }.task-facts span, .decision-facts span, dt { display: block; color: #70849e; font-size: 9px; }.task-facts strong, .decision-facts b, dd { display: block; margin-top: 4px; overflow-wrap: anywhere; font-size: 10px; }
.hash { font-family: ui-monospace, monospace; }.decision-card { margin-top: 14px; padding: 16px; border: 1px solid #284766; border-radius: 9px; background: linear-gradient(145deg, #10233a, #0a1524); }.decision-card header > strong { color: #73c3ff; }.decision-code { display: inline-block; margin: 12px 0; padding: 4px 8px; border-radius: 99px; background: #1d3855; color: #8dccff; font: 10px ui-monospace, monospace; }
.summary { color: #bdc9d8; line-height: 1.65; }.notice { padding: 9px 11px; border-left: 3px solid #62c39d; background: #123428; color: #8fe1c1; font-size: 11px; }.notice.warn { border-color: #d59b3b; background: #392b16; color: #f1c36f; }.notice.danger { border-color: #e25f6c; background: #3b1c24; color: #ff9ca6; }
.id-list { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 12px 0; }.id-list div { padding: 8px; border-radius: 6px; background: #091422; }dd { margin-left: 0; }.decision-findings { display: grid; gap: 7px; }.decision-findings article, .finding { padding: 8px; border: 1px solid #1d3148; border-radius: 6px; }.decision-findings code, .finding code { display: block; color: #70baff; font-size: 9px; }.decision-findings b, .finding b { display: block; margin-top: 4px; font-size: 11px; }.decision-findings p, .finding span { display: block; margin: 4px 0 0; color: #96a9c0; font-size: 10px; }.decision-findings small, .finding small { display: block; margin-top: 5px; color: #70849c; overflow-wrap: anywhere; font-size: 9px; }
.specialty-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }.specialty-card { min-width: 0; padding: 14px; border: 1px solid #20364f; border-radius: 8px; background: #091522; }.specialty-card header > code { color: #79c3ff; font-size: 9px; }.specialty-card > p { color: #a8b7c9; font-size: 11px; line-height: 1.6; }.position-card { border-color: #5b3a45; }
.reason { display: grid; gap: 4px; margin: 7px 0; padding: 7px; border-radius: 6px; background: #321a20; }.reason code { color: #ff8f99; }.reason span { font-size: 10px; }details { margin-top: 8px; }summary { color: #72baff; font-size: 10px; cursor: pointer; }pre { max-height: 260px; overflow: auto; padding: 10px; background: #050b13; color: #b6c5d7; white-space: pre-wrap; overflow-wrap: anywhere; font: 10px/1.55 ui-monospace, monospace; }
.veto { margin-top: 8px; padding: 9px; border: 1px solid #7b3f4b; border-radius: 6px; background: #321821; }.veto div { display: flex; justify-content: space-between; gap: 8px; }.veto code { color: #ff8591; }.veto p { color: #efb6bc; font-size: 10px; }.veto small { color: #9d7b83; overflow-wrap: anywhere; }
.reason-section { margin-top: 12px; padding: 14px; border: 1px solid #20364f; border-radius: 8px; background: #091522; }.reason-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; }.reason-grid article { min-width: 0; padding: 8px; border: 1px solid #1c3047; border-radius: 6px; }.reason-grid code { color: #75bbfa; overflow-wrap: anywhere; }.reason-grid b { display: block; margin-top: 5px; color: #899db6; font-size: 9px; }.reason-grid p { margin: 5px 0 0; color: #aab8c9; font-size: 9px; overflow-wrap: anywhere; }
.boundary { color: #71849c !important; font-size: 9px !important; }.empty { padding: 28px; color: #768aa4; text-align: center; }.empty.compact { padding: 12px; }
@media (max-width: 1450px) { .task-facts, .decision-facts { grid-template-columns: repeat(3, 1fr); }.specialty-grid { grid-template-columns: 1fr 1fr; }.position-card { grid-column: 1 / -1; } }
</style>
