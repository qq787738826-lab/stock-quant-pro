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
import type {
  PreviewMode,
  PreviewQualification,
} from '../../research-preview/types'
import type { AgentRun } from '../../agent-team/types'

const props = defineProps<{
  runs: AgentRun[]
  mode: PreviewMode
  qualification: PreviewQualification
}>()

const slots = computed(() => buildAgentSlots(props.runs))
</script>

<template>
  <section class="agent-section">
    <div class="section-heading">
      <div><p class="eyebrow">SIX SPECIALISTS</p><h2>六个专业智能体</h2></div>
      <code>{{ qualification }}</code>
    </div>
    <div class="agent-grid">
      <article
        v-for="slot in slots"
        :key="slot.agentCode"
        :class="['agent-card', { missing: !slot.run, veto: slot.agentCode === 'POSITION_RISK' && slot.run?.veto }]"
      >
        <header>
          <div><h3>{{ AGENT_NAMES[slot.agentCode] }}</h3><code>{{ slot.agentCode }}</code></div>
          <span class="status">{{ slot.run?.status ?? '暂无' }}</span>
        </header>
        <div class="score-row">
          <div><span>评分</span><strong>{{ displayValue(slot.run?.score) }}</strong></div>
          <div><span>置信度</span><strong>{{ displayValue(slot.run?.confidence) }}</strong></div>
        </div>
        <dl>
          <div><dt>runId</dt><dd>{{ displayValue(slot.run?.id) }}</dd></div>
          <div><dt>gateStatus</dt><dd>{{ displayValue(slot.run?.gateStatus) }}</dd></div>
          <div><dt>decision</dt><dd>{{ displayValue(slot.run?.decision) }}</dd></div>
          <div><dt>veto声明</dt><dd>{{ slot.run ? (slot.run.veto ? '是' : '否') : '暂无' }}</dd></div>
          <div><dt>开始时间</dt><dd>{{ displayTime(slot.run?.startedAt) }}</dd></div>
          <div><dt>完成时间</dt><dd>{{ displayTime(slot.run?.finishedAt) }}</dd></div>
          <div><dt>耗时</dt><dd>{{ slot.run?.durationMs == null ? '暂无' : `${slot.run.durationMs} ms` }}</dd></div>
          <div><dt>数据模式</dt><dd>{{ mode }}</dd></div>
        </dl>
        <p class="summary">{{ slot.run?.summary || '暂无摘要' }}</p>
        <p v-if="slot.run?.errorMessage" class="error-message">{{ slot.run.errorMessage }}</p>

        <div class="detail-block">
          <b>Findings / reasonCode / evidenceIds</b>
          <div v-if="runFindings(slot.run).length" class="finding-list">
            <article v-for="finding in runFindings(slot.run)" :key="finding.findingId">
              <div><code>{{ finding.code }}</code><span>{{ finding.severity }}</span></div>
              <strong>{{ finding.title }}</strong>
              <p>{{ finding.detail }}</p>
              <small>evidenceIds：{{ finding.evidenceIds.join(', ') || '暂无' }}</small>
            </article>
          </div>
          <p v-else class="empty">暂无finding</p>
          <div v-for="error in runErrors(slot.run)" :key="error.code" class="structured-error">
            <code>{{ error.code }}</code><span>{{ error.message }}</span>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.agent-section { min-width: 0; }.section-heading, .agent-card header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.section-heading { margin: 0 2px 13px; }.section-heading h2 { margin: 0; font-size: 20px; }.eyebrow { margin: 0 0 4px; color: #5faeff; font-size: 10px; font-weight: 800; letter-spacing: .15em; }
.section-heading > code { color: #65d7ab; }.agent-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.agent-card { min-width: 0; padding: 17px; border: 1px solid #223b59; border-top: 3px solid #3779b8; border-radius: 10px; background: linear-gradient(145deg, #101e32, #0b1525); }
.agent-card.veto { border-top-color: #e45f6c; }.agent-card.missing { border-top-color: #64748b; opacity: .82; }
h3 { margin: 0 0 3px; font-size: 16px; }code { color: #78bdfd; font-size: 10px; }.status { padding: 3px 7px; border-radius: 99px; background: #20334b; color: #b7c7da; font-size: 10px; }
.score-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 14px 0; }.score-row div { padding: 9px; border: 1px solid #22364f; border-radius: 7px; background: #091422; }
.score-row span, dt { display: block; margin-bottom: 3px; color: #7286a0; font-size: 10px; }.score-row strong { color: #6ab9ff; font: 700 20px ui-monospace, monospace; }
dl { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 0; }dl div { min-width: 0; }dd { margin: 0; overflow-wrap: anywhere; color: #c5d0df; font-size: 11px; }
.summary { min-height: 42px; margin: 14px 0; color: #aebed1; font-size: 12px; line-height: 1.6; }.error-message { color: #ff8b96; font-size: 11px; overflow-wrap: anywhere; }
.detail-block { padding-top: 12px; border-top: 1px solid #21334a; }.detail-block > b { color: #8397b1; font-size: 10px; }
.finding-list { display: grid; gap: 7px; margin-top: 8px; }.finding-list article { padding: 8px; border-radius: 6px; background: #0a1524; }.finding-list article div { display: flex; justify-content: space-between; gap: 8px; }.finding-list span { color: #ffc267; font-size: 10px; }
.finding-list strong { display: block; margin-top: 5px; font-size: 11px; }.finding-list p { margin: 4px 0; color: #9badc3; font-size: 10px; line-height: 1.5; }.finding-list small { color: #6f829b; overflow-wrap: anywhere; }
.empty { margin: 8px 0 0; color: #6f829b; font-size: 10px; }.structured-error { display: grid; gap: 3px; margin-top: 8px; padding: 8px; border: 1px solid #69404a; border-radius: 6px; }.structured-error span { color: #f0a0a8; font-size: 10px; }
@media (max-width: 1450px) { .agent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
