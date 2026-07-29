<script setup lang="ts">
import { computed } from 'vue'
import { AGENT_NAMES } from '../../agent-team/presentation'
import {
  buildAgentSlots,
  displayTime,
  displayValue,
  runErrors,
  runFindings,
} from '../../research-preview/presentation'
import type { AgentCode, AgentRun, FormalVeto } from '../../agent-team/types'

const props = defineProps<{
  runs: AgentRun[]
  vetoes: FormalVeto[]
  executionMode: string | null
}>()

const slots = computed(() => buildAgentSlots(props.runs))

function formalVetoFor(agentCode: AgentCode): FormalVeto[] {
  return props.vetoes.filter((veto) => veto.agentCode === agentCode)
}

function primaryReason(run: AgentRun | null): string {
  return runErrors(run)[0]?.code ?? runFindings(run)[0]?.code ?? '暂无'
}

function formatOutput(run: AgentRun | null): string {
  return run?.outputJson == null ? '暂无' : JSON.stringify(run.outputJson, null, 2)
}
</script>

<template>
  <section class="agent-section">
    <div class="section-heading">
      <div>
        <p class="eyebrow">SIX SPECIALISTS · PERSISTED RESULTS</p>
        <h2>六个专业智能体</h2>
        <span>默认呈现业务摘要；完整运行字段在每张卡片的技术详情中按需展开。</span>
      </div>
      <b>固定6个 · 无第七智能体</b>
    </div>

    <div class="agent-grid">
      <article
        v-for="slot in slots"
        :key="slot.agentCode"
        :class="[
          'agent-card',
          {
            missing: !slot.run,
            'dq-blocked': slot.agentCode === 'DATA_QUALITY' && slot.run?.gateStatus === 'BLOCKED',
            'formal-veto': formalVetoFor(slot.agentCode).length > 0,
          },
        ]"
      >
        <header>
          <div>
            <h3>{{ AGENT_NAMES[slot.agentCode] }}</h3>
            <code>{{ slot.agentCode }}</code>
          </div>
          <span class="status">{{ slot.run?.status ?? '暂无' }}</span>
        </header>

        <div class="primary-metrics">
          <div><span>gateStatus</span><strong>{{ displayValue(slot.run?.gateStatus) }}</strong></div>
          <div><span>score</span><strong>{{ displayValue(slot.run?.score) }}</strong></div>
          <div><span>confidence</span><strong>{{ displayValue(slot.run?.confidence) }}</strong></div>
        </div>

        <p class="summary">{{ slot.run?.summary || '暂无摘要' }}</p>
        <div class="card-signals">
          <span>decision <b>{{ displayValue(slot.run?.decision) }}</b></span>
          <span>finding <b>{{ runFindings(slot.run).length }}项</b></span>
          <span>主要原因 <b>{{ primaryReason(slot.run) }}</b></span>
        </div>
        <p v-if="slot.agentCode === 'DATA_QUALITY' && slot.run?.gateStatus === 'BLOCKED'" class="alert warning">
          数据质量已阻断后续研究结论。
        </p>
        <p v-if="formalVetoFor(slot.agentCode).length" class="alert danger">
          存在{{ formalVetoFor(slot.agentCode).length }}条正式POSITION_RISK veto记录。
        </p>

        <details class="technical-details">
          <summary>技术详情</summary>
          <dl>
            <div><dt>runId</dt><dd>{{ displayValue(slot.run?.id) }}</dd></div>
            <div><dt>attemptNo</dt><dd>{{ displayValue(slot.run?.attemptNo) }}</dd></div>
            <div><dt>开始时间</dt><dd>{{ displayTime(slot.run?.startedAt) }}</dd></div>
            <div><dt>完成时间</dt><dd>{{ displayTime(slot.run?.finishedAt) }}</dd></div>
            <div><dt>durationMs</dt><dd>{{ displayValue(slot.run?.durationMs) }}</dd></div>
            <div><dt>executionMode</dt><dd>{{ displayValue(executionMode) }}</dd></div>
            <div><dt>run veto声明</dt><dd>{{ slot.run ? (slot.run.veto ? '是（非正式记录本身）' : '否') : '暂无' }}</dd></div>
            <div><dt>errorMessage</dt><dd>{{ displayValue(slot.run?.errorMessage) }}</dd></div>
          </dl>

          <section class="detail-group">
            <h4>完整findings与evidenceIds</h4>
            <div v-if="runFindings(slot.run).length" class="finding-list">
              <article v-for="finding in runFindings(slot.run)" :key="finding.findingId">
                <div><code>{{ finding.code }}</code><span>{{ finding.severity }}</span></div>
                <strong>{{ finding.title }}</strong>
                <p>{{ finding.detail }}</p>
                <small>evidenceIds：{{ finding.evidenceIds.join(', ') || '暂无' }}</small>
              </article>
            </div>
            <p v-else class="empty">暂无finding</p>
          </section>

          <section class="detail-group">
            <h4>结构化errors</h4>
            <div v-for="error in runErrors(slot.run)" :key="error.code" class="structured-error">
              <code>{{ error.code }}</code><span>{{ error.message }}</span>
            </div>
            <p v-if="!runErrors(slot.run).length" class="empty">暂无结构化error</p>
          </section>

          <details class="output-details">
            <summary>查看完整outputJson</summary>
            <pre>{{ formatOutput(slot.run) }}</pre>
          </details>
        </details>
      </article>
    </div>
  </section>
</template>

<style scoped>
.agent-section { min-width: 0; }
.section-heading, .agent-card header { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.section-heading { margin: 0 2px 13px; }
.section-heading h2 { margin: 0; font-size: 20px; }
.section-heading span { display: block; margin-top: 5px; color: #9badc1; font-size: 13px; }
.section-heading > b { color: #7fc7f8; font-size: 12px; }
.eyebrow { margin: 0 0 4px; color: #73b7f2; font-size: 11px; font-weight: 800; letter-spacing: .13em; }
.agent-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.agent-card {
  min-width: 0;
  padding: 16px;
  border: 1px solid #29425e;
  border-top: 3px solid #4c91cd;
  border-radius: 10px;
  background: linear-gradient(145deg, #101f32, #0b1727);
}
.agent-card.missing { border-top-color: #64748b; opacity: .84; }
.agent-card.dq-blocked, .agent-card.formal-veto { border-color: #80434d; border-top-color: #dc626d; }
h3 { margin: 0 0 3px; font-size: 17px; }
code { color: #82c2f3; font-size: 11px; overflow-wrap: anywhere; }
.status { padding: 4px 8px; border-radius: 999px; background: #20364e; color: #c3cfdd; font-size: 12px; }
.primary-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; margin: 14px 0; }
.primary-metrics div { min-width: 0; padding: 9px; border-radius: 7px; background: #091523; }
.primary-metrics span { display: block; color: #91a4ba; font-size: 12px; }
.primary-metrics strong { display: block; margin-top: 5px; overflow-wrap: anywhere; color: #dfe8f2; font-size: 15px; }
.summary { min-height: 44px; margin: 0 0 12px; color: #c1cddc; font-size: 13px; line-height: 1.65; }
.card-signals { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.card-signals span { min-width: 0; padding: 7px; border-radius: 6px; background: #0c1d2f; color: #93a6bc; font-size: 12px; }
.card-signals span:last-child { grid-column: 1 / -1; }
.card-signals b { display: block; margin-top: 4px; overflow-wrap: anywhere; color: #cbd6e2; }
.alert { margin: 10px 0 0; padding: 8px; border-left: 3px solid; font-size: 12px; }
.alert.warning { border-color: #d49a43; background: #352817; color: #e8bf79; }
.alert.danger { border-color: #dc626d; background: #381d24; color: #efa2aa; }
.technical-details { margin-top: 13px; border-top: 1px solid #263b53; }
.technical-details > summary { padding-top: 11px; color: #82bff0; font-size: 12px; cursor: pointer; }
.technical-details dl { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; margin: 12px 0; }
.technical-details dl div { min-width: 0; padding: 7px; border-radius: 6px; background: #091523; }
dt { color: #91a4ba; font-size: 11px; }
dd { margin: 4px 0 0; overflow-wrap: anywhere; color: #c8d3df; font-size: 12px; }
.detail-group { margin-top: 12px; }
.detail-group h4 { margin: 0 0 7px; color: #b9c7d6; font-size: 13px; }
.finding-list { display: grid; gap: 7px; }
.finding-list article { padding: 8px; border-radius: 6px; background: #091523; }
.finding-list article div { display: flex; justify-content: space-between; gap: 8px; }
.finding-list span { color: #e7b869; font-size: 11px; }
.finding-list strong { display: block; margin-top: 5px; font-size: 13px; }
.finding-list p { margin: 4px 0; color: #a8b7c8; font-size: 12px; line-height: 1.55; }
.finding-list small { color: #93a5ba; font-size: 11px; overflow-wrap: anywhere; }
.structured-error { display: grid; gap: 3px; margin-top: 7px; padding: 8px; border: 1px solid #704149; border-radius: 6px; }
.structured-error span { color: #e9abb1; font-size: 12px; }
.output-details { margin-top: 12px; }
.output-details summary { color: #82bff0; font-size: 12px; cursor: pointer; }
pre {
  max-height: 270px;
  overflow: auto;
  padding: 10px;
  border-radius: 6px;
  background: #050b13;
  color: #b8c7d8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: 11px/1.55 ui-monospace, monospace;
}
.empty { color: #93a5ba; font-size: 12px; }
@media (max-width: 1450px) {
  .agent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 820px) {
  .agent-grid { grid-template-columns: 1fr; }
}
</style>
