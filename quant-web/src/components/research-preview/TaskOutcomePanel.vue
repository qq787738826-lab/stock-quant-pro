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
  PreviewTaskBundle,
} from '../../research-preview/types'

const props = defineProps<{
  bundle: PreviewTaskBundle | null
  issues: PreviewIssue[]
}>()

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

function primaryCode(run: typeof backtest.value): string {
  return runErrors(run)[0]?.code ?? runFindings(run)[0]?.code ?? '暂无'
}
</script>

<template>
  <section class="outcome-panel">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">CHIEF DECISION &amp; RISK SUMMARY</p>
        <h2>总控结论与专业风险摘要</h2>
      </div>
      <span>{{ bundle?.task.status ?? '暂无任务状态' }}</span>
    </div>

    <div v-if="!bundle" class="empty">
      请选择已有候选或Agent历史任务。页面不会创建、刷新、重试或重新分析任务。
    </div>
    <template v-else>
      <section :class="['decision-card', { vetoed: decision?.vetoed }]">
        <div class="decision-title">
          <div>
            <span>总控中文结论</span>
            <strong>{{ decision ? FINAL_DECISION_NAMES[decision.decision] : '暂无' }}</strong>
          </div>
          <code>{{ decision?.decision ?? '暂无decision code' }}</code>
        </div>
        <div v-if="!decision" class="empty compact">总控结果尚未持久化，不显示中性或安全结论。</div>
        <template v-else>
          <div class="decision-facts">
            <div><span>gateStatus</span><b>{{ decision.gateStatus }}</b></div>
            <div><span>正式veto</span><b>{{ decision.vetoed ? '存在' : '无' }}</b></div>
            <div><span>score</span><b>{{ displayValue(decision.score) }}</b></div>
            <div><span>confidence</span><b>{{ displayValue(decision.confidence) }}</b></div>
          </div>
          <p class="summary">{{ decision.summary }}</p>
          <p v-if="decision.decision === 'PASS_TO_MANUAL_REVIEW'" class="notice">
            该结果仅表示进入人工研究复核，不表示任何交易指令。
          </p>
          <p v-if="decision.decision === 'BLOCKED_BY_DATA_QUALITY'" class="notice warning">
            数据质量阻断不是POSITION_RISK正式风险否决。
          </p>
          <p v-if="decision.decision === 'REJECTED_BY_VETO'" class="notice danger">
            该结论必须由POSITION_RISK正式否决记录支持。
          </p>
          <p v-if="decision.decision === 'INSUFFICIENT_DATA'" class="notice warning">
            数据不足不能解释为中性、安全或可执行结论。
          </p>
          <details v-if="(decision.findings ?? []).length" class="decision-details">
            <summary>展开总控findings（{{ decision.findings?.length ?? 0 }}项）</summary>
            <article v-for="finding in decision.findings ?? []" :key="finding.findingId">
              <code>{{ finding.code }}</code>
              <b>{{ finding.title }} · {{ finding.severity }}</b>
              <p>{{ finding.detail }}</p>
            </article>
          </details>
        </template>
      </section>

      <div class="risk-grid">
        <article class="risk-card">
          <header><h3>策略回测</h3><code>{{ backtestState }}</code></header>
          <div class="risk-facts">
            <span>状态 <b>{{ backtest?.status ?? '暂无' }}</b></span>
            <span>主要原因 <b>{{ primaryCode(backtest) }}</b></span>
          </div>
          <p>{{ backtest?.summary || '暂无已有STRATEGY_BACKTEST结果' }}</p>
          <small>不启动新回测，不用其他结果替代，不补造缺失值。</small>
        </article>

        <article class="risk-card">
          <header><h3>公告风险</h3><code>{{ announcement?.status ?? '暂无' }}</code></header>
          <div class="risk-facts">
            <span>finding <b>{{ runFindings(announcement).length }}项</b></span>
            <span>主要原因 <b>{{ primaryCode(announcement) }}</b></span>
          </div>
          <p>{{ announcement?.summary || '暂无已有公告风险结果' }}</p>
          <small>只展示已有研究级事实与原始时间精度边界。</small>
        </article>

        <article :class="['risk-card', 'position-card', { vetoed: bundle.vetoes.length }]">
          <header><h3>持仓风险</h3><code>{{ position?.status ?? '暂无' }}</code></header>
          <div class="risk-facts">
            <span>正式veto <b>{{ bundle.vetoes.length }}项</b></span>
            <span>主要原因 <b>{{ primaryCode(position) }}</b></span>
          </div>
          <p>{{ position?.summary || '暂无已有仓位风险结果' }}</p>
          <div v-if="!bundle.vetoes.length" class="no-veto">无正式veto记录</div>
          <details v-for="veto in bundle.vetoes" v-else :key="veto.vetoId" class="formal-veto">
            <summary>{{ veto.vetoCode }} · 展开正式veto</summary>
            <dl>
              <div><dt>vetoId</dt><dd>{{ veto.vetoId }}</dd></div>
              <div><dt>reason</dt><dd>{{ veto.reason }}</dd></div>
              <div><dt>agent</dt><dd>{{ veto.agentCode }}</dd></div>
              <div><dt>runId</dt><dd>{{ veto.runId }}</dd></div>
              <div><dt>evidenceIds</dt><dd>{{ (veto.evidenceIds ?? []).join(', ') || '暂无' }}</dd></div>
            </dl>
          </details>
          <small>POSITION_RISK是唯一正式否决来源；页面不发出仓位操作指令。</small>
        </article>
      </div>

      <section class="reason-summary">
        <header>
          <div><h3>结构化原因摘要</h3><span>只读取明确结构字段，不解析自然语言摘要</span></div>
          <b>{{ reasons.length }}项</b>
        </header>
        <div v-if="reasons.length" class="reason-list">
          <article v-for="reason in reasons.slice(0, 8)" :key="`${reason.source}-${reason.agentCode}-${reason.code}`">
            <code>{{ reason.code }}</code>
            <span>{{ reason.agentCode ? AGENT_NAMES[reason.agentCode] : reason.source }}</span>
          </article>
        </div>
        <div v-else class="empty compact">暂无明确结构化reasonCode</div>
      </section>

      <p class="generated-at">总控生成时间：{{ displayTime(decision?.generatedAt) }}</p>
    </template>
  </section>
</template>

<style scoped>
.outcome-panel { min-width: 0; padding: 18px; border: 1px solid #263f5b; border-radius: 12px; background: #0e1b2c; }
.panel-heading, .risk-card header, .reason-summary header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h2, h3 { margin: 0; }.panel-heading h2 { font-size: 19px; }
.eyebrow { margin: 0 0 5px; color: #73b7f2; font-size: 11px; font-weight: 800; letter-spacing: .13em; }
.panel-heading > span { color: #9eb0c5; font-size: 12px; }
.decision-card { margin-top: 14px; padding: 16px; border: 1px solid #2b4a67; border-radius: 10px; background: #0a1727; }
.decision-card.vetoed { border-color: #82434d; background: #2a1820; }
.decision-title { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.decision-title span { display: block; color: #95a8be; font-size: 12px; }
.decision-title strong { display: block; margin-top: 5px; color: #edf3fa; font-size: 21px; }
.decision-title code { color: #86c5f5; font-size: 11px; }
.decision-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 14px; }
.decision-facts div { padding: 9px 10px; border-radius: 7px; background: #0d2135; }
.decision-facts span { display: block; color: #91a5bc; font-size: 12px; }
.decision-facts b { display: block; margin-top: 4px; color: #d9e4ef; font-size: 13px; }
.summary { color: #c3cfdd; font-size: 14px; line-height: 1.7; }
.notice { padding: 9px 11px; border-left: 3px solid #57bd90; background: #123328; color: #9adfc4; font-size: 13px; }
.notice.warning { border-color: #d49a43; background: #342716; color: #eac17b; }
.notice.danger { border-color: #dc626d; background: #381c23; color: #f0a1a9; }
.decision-details { margin-top: 12px; border-top: 1px solid #263d57; }
.decision-details summary { padding-top: 11px; color: #82bdf0; font-size: 12px; cursor: pointer; }
.decision-details article { margin-top: 8px; padding: 9px; border-radius: 7px; background: #081522; }
.decision-details article b { display: block; margin-top: 4px; font-size: 13px; }
.decision-details article p { color: #a9b8ca; font-size: 13px; }
.risk-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.risk-card { min-width: 0; padding: 14px; border: 1px solid #243b55; border-radius: 9px; background: #091624; }
.risk-card.position-card { border-color: #4d3c4a; }
.risk-card.vetoed { border-color: #84444e; background: #281820; }
.risk-card h3 { font-size: 15px; }
.risk-card header code { color: #83c0ef; font-size: 11px; }
.risk-facts { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; margin-top: 10px; }
.risk-facts span { padding: 7px; border-radius: 6px; background: #0d2032; color: #92a6bd; font-size: 12px; }
.risk-facts b { display: block; margin-top: 4px; overflow-wrap: anywhere; color: #d5dfeb; }
.risk-card > p { color: #b7c4d3; font-size: 13px; line-height: 1.6; }
.risk-card > small { display: block; color: #93a6bc; font-size: 12px; line-height: 1.5; }
.no-veto { margin: 8px 0; color: #92a5bc; font-size: 12px; }
.formal-veto { margin: 9px 0; border: 1px solid #85434d; border-radius: 7px; background: #351c23; }
.formal-veto summary { padding: 9px; color: #f1a3ab; font-size: 12px; cursor: pointer; }
.formal-veto dl { display: grid; gap: 7px; margin: 0; padding: 0 9px 9px; }
.formal-veto dl div { min-width: 0; }
.formal-veto dt { color: #b98f97; font-size: 12px; }
.formal-veto dd { margin: 3px 0 0; overflow-wrap: anywhere; color: #e6c6ca; font-size: 12px; }
.reason-summary { margin-top: 12px; padding: 14px; border: 1px solid #233a54; border-radius: 9px; background: #091624; }
.reason-summary header div span { display: block; margin-top: 4px; color: #93a6bd; font-size: 12px; }
.reason-summary header > b { color: #82bff1; font-size: 13px; }
.reason-list { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; margin-top: 10px; }
.reason-list article { min-width: 0; padding: 8px; border-radius: 7px; background: #0d2032; }
.reason-list code { display: block; color: #82bff1; font-size: 11px; overflow-wrap: anywhere; }
.reason-list span { display: block; margin-top: 4px; color: #a3b3c6; font-size: 12px; }
.generated-at { margin: 11px 0 0; color: #8fa2b8; font-size: 12px; text-align: right; }
.empty { padding: 28px; color: #91a4ba; font-size: 13px; text-align: center; }
.empty.compact { padding: 12px; }
@media (max-width: 1250px) {
  .risk-grid { grid-template-columns: 1fr 1fr; }
  .position-card { grid-column: 1 / -1; }
  .reason-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 820px) {
  .decision-facts, .risk-grid { grid-template-columns: 1fr 1fr; }
  .position-card { grid-column: auto; }
}
</style>
