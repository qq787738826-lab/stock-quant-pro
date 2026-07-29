<script setup lang="ts">
import { computed } from 'vue'
import { AGENT_NAMES, FINAL_DECISION_NAMES } from '../../agent-team/presentation'
import {
  buildAgentSlots,
  dataQualityGateLabel,
  extractReasonCodes,
  researchEvidenceCompletenessLabel,
  researchActionForDecision,
} from '../../research-preview/presentation'
import type {
  PreviewCandidate,
  PreviewIssue,
  PreviewQualification,
  PreviewTaskBundle,
  ScanTaskSnapshot,
} from '../../research-preview/types'

const props = defineProps<{
  bundle: PreviewTaskBundle | null
  candidate: PreviewCandidate | null
  scanTask: ScanTaskSnapshot | null
  qualification: PreviewQualification
  synthetic: boolean
  issues: PreviewIssue[]
}>()

const decision = computed(() => props.bundle?.decision ?? null)
const slots = computed(() => buildAgentSlots(props.bundle?.runs))
const reasons = computed(() => extractReasonCodes(props.bundle, props.issues).slice(0, 5))
const completedCount = computed(() =>
  slots.value.filter((slot) => slot.run?.status === 'COMPLETED').length,
)
const symbol = computed(() => props.bundle?.task.symbol ?? props.candidate?.symbol ?? '暂无标的')
const name = computed(() => props.candidate?.name ?? '名称暂无')
const tradeDate = computed(() =>
  props.bundle?.task.tradeDate ?? props.candidate?.tradeDate ?? props.scanTask?.trade_date ?? '暂无',
)
const decisionName = computed(() =>
  decision.value ? FINAL_DECISION_NAMES[decision.value.decision] : '暂无总控结论',
)
const action = computed(() => researchActionForDecision(decision.value?.decision))
const dataQualityGate = computed(() => dataQualityGateLabel(props.bundle))
const evidenceCompleteness = computed(() => researchEvidenceCompletenessLabel(props.bundle))
const hasFormalVeto = computed(() => (props.bundle?.vetoes.length ?? 0) > 0)
const decisionTone = computed(() => {
  if (decision.value?.decision === 'REJECTED_BY_VETO') return 'danger'
  if (
    decision.value?.decision === 'BLOCKED_BY_DATA_QUALITY'
    || decision.value?.decision === 'INSUFFICIENT_DATA'
  ) return 'warning'
  if (decision.value?.decision === 'PASS_TO_MANUAL_REVIEW') return 'success'
  return 'info'
})
</script>

<template>
  <section :class="['preview-overview-panel', `tone-${decisionTone}`]">
    <header class="preview-overview-header">
      <div class="preview-overview-identity">
        <div class="preview-overview-marker-row">
          <span class="preview-overview-current-marker">当前分析</span>
          <span v-if="synthetic" class="preview-overview-demo-marker">TEST_DEMO_EXPLICIT</span>
        </div>
        <div class="preview-overview-title">
          <strong>{{ symbol }}</strong>
          <h1>{{ name }}</h1>
        </div>
        <div class="preview-overview-meta">
          <span>交易日期 {{ tradeDate }}</span>
          <span>扫描任务 #{{ scanTask?.id ?? '暂无' }}</span>
        </div>
        <div class="preview-overview-qualification">
          <span>数据资格</span>
          <strong>{{ qualification }}</strong>
          <small>{{ synthetic ? 'SYNTHETIC · NOT_REAL_MARKET_RESULT' : 'READ_ONLY · EXISTING_RESULT' }}</small>
        </div>
      </div>
    </header>

    <div class="preview-overview-decision">
      <article class="preview-overview-chief">
        <span>总控综合结论</span>
        <strong>{{ decisionName }}</strong>
        <code>{{ decision?.decision ?? '暂无decision code' }}</code>
        <p>{{ decision?.summary ?? '当前没有已加载的总控结果。' }}</p>
      </article>
      <div class="preview-overview-facts">
        <article>
          <span>研究动作</span>
          <strong>{{ action }}</strong>
        </article>
        <article>
          <span>数据质量门禁</span>
          <strong>{{ dataQualityGate }}</strong>
        </article>
        <article>
          <span>研究证据完整性</span>
          <strong>{{ evidenceCompleteness }}</strong>
        </article>
        <article :class="{ critical: hasFormalVeto }">
          <span>正式veto</span>
          <strong>{{ hasFormalVeto ? '存在' : '无' }}</strong>
        </article>
        <article>
          <span>六智能体完成状态</span>
          <strong>{{ completedCount }} / 6 COMPLETED</strong>
        </article>
      </div>
    </div>

    <div class="preview-overview-reasons">
      <div class="preview-overview-reason-heading">
        <strong>最重要的结构化原因</strong>
        <span>{{ reasons.length ? `显示前${reasons.length}项` : '暂无明确原因' }}</span>
      </div>
      <div v-if="reasons.length" class="preview-overview-reason-list">
        <article v-for="reason in reasons" :key="`${reason.source}-${reason.agentCode}-${reason.code}`">
          <code>{{ reason.code }}</code>
          <span>{{ reason.agentCode ? AGENT_NAMES[reason.agentCode] : reason.source }}</span>
          <p>{{ reason.detail || '暂无附加详情' }}</p>
        </article>
      </div>
      <p v-else class="preview-overview-empty-reason">
        当前结果没有明确结构化reasonCode；页面不会从摘要文本推测。
      </p>
    </div>

    <div class="preview-overview-agent-strip" aria-label="六智能体运行状态">
      <div v-for="slot in slots" :key="slot.agentCode">
        <i
          :class="[
            'preview-overview-status-dot',
            (slot.run?.gateStatus ?? 'missing').toLowerCase(),
          ]"
        />
        <span>{{ AGENT_NAMES[slot.agentCode] }}</span>
        <b>{{ slot.run?.status ?? '暂无' }}</b>
      </div>
    </div>
  </section>
</template>

<style scoped>
.preview-overview-panel {
  position: static;
  float: none;
  transform: none;
  min-width: 0;
  height: auto;
  border: 1px solid #294763;
  border-left: 4px solid #4c9dde;
  border-radius: 14px;
  background:
    radial-gradient(circle at 86% -35%, rgba(67, 146, 218, .22), transparent 42%),
    linear-gradient(145deg, #11233a, #0b1728);
  box-shadow: 0 16px 34px rgba(0, 0, 0, .18);
}
.preview-overview-panel.tone-warning { border-left-color: #d59a43; }
.preview-overview-panel.tone-danger { border-left-color: #df6670; }
.preview-overview-panel.tone-success { border-left-color: #52bd91; }
.preview-overview-header {
  display: block;
  position: static;
  float: none;
  transform: none;
  min-width: 0;
  height: auto;
  padding: 20px 22px 16px;
  border-bottom: 1px solid #233b55;
}
.preview-overview-identity { min-width: 0; }
.preview-overview-marker-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.preview-overview-current-marker, .preview-overview-demo-marker {
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}
.preview-overview-current-marker { color: #85c9ff; background: #183a58; }
.preview-overview-demo-marker {
  color: #ffd182;
  background: #4a3518;
  font-family: ui-monospace, monospace;
}
.preview-overview-title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 12px;
  min-width: 0;
  margin-top: 9px;
}
.preview-overview-title strong {
  overflow-wrap: anywhere;
  color: #78c6ff;
  font: 700 22px ui-monospace, monospace;
}
.preview-overview-title h1 {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: #f1f6fc;
  font-size: 27px;
}
.preview-overview-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 18px;
  min-width: 0;
  margin-top: 7px;
}
.preview-overview-meta span {
  overflow-wrap: anywhere;
  color: #9fb0c5;
  font-size: 13px;
}
.preview-overview-qualification {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  min-width: 0;
  margin-top: 10px;
}
.preview-overview-qualification span {
  color: #9bacc1;
  font-size: 12px;
}
.preview-overview-qualification strong,
.preview-overview-qualification small {
  min-width: 0;
  padding: 3px 8px;
  border: 1px solid #28506b;
  border-radius: 999px;
  overflow-wrap: anywhere;
  white-space: normal;
}
.preview-overview-qualification strong {
  color: #75d4ad;
  font: 700 13px ui-monospace, monospace;
}
.preview-overview-qualification small {
  color: #9bacc1;
  font-size: 12px;
}
.preview-overview-decision {
  display: grid;
  position: static;
  float: none;
  transform: none;
  grid-template-columns: minmax(300px, 1.2fr) minmax(420px, 1fr);
  gap: 16px;
  min-width: 0;
  height: auto;
  padding: 18px 22px;
}
.preview-overview-chief {
  min-width: 0;
  padding: 16px;
  border: 1px solid #2b4b69;
  border-radius: 10px;
  background: rgba(7, 18, 31, .55);
}
.preview-overview-chief > span { color: #9db0c6; font-size: 13px; }
.preview-overview-chief > strong { display: block; margin: 7px 0 5px; color: #f2f6fc; font-size: 23px; }
.preview-overview-chief code { color: #80c5ff; font-size: 11px; }
.preview-overview-chief p { margin: 10px 0 0; color: #c1cedd; font-size: 14px; line-height: 1.7; }
.preview-overview-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.preview-overview-facts article {
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid #233b55;
  border-radius: 9px;
  background: #0a1727;
}
.preview-overview-facts article.critical { border-color: #87434d; background: #2d1820; }
.preview-overview-facts span { display: block; color: #93a5bb; font-size: 12px; }
.preview-overview-facts strong {
  display: block;
  margin-top: 7px;
  color: #e5edf7;
  font-size: 14px;
  line-height: 1.45;
}
.preview-overview-reasons {
  position: static;
  float: none;
  transform: none;
  min-width: 0;
  height: auto;
  padding: 0 22px 18px;
}
.preview-overview-reason-heading {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 9px;
}
.preview-overview-reason-heading strong { font-size: 14px; }
.preview-overview-reason-heading span { color: #91a3b9; font-size: 12px; }
.preview-overview-reason-list {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.preview-overview-reason-list article {
  min-width: 0;
  padding: 10px;
  border: 1px solid #20364f;
  border-radius: 8px;
  background: #091523;
}
.preview-overview-reason-list code { color: #79bdfa; font-size: 11px; overflow-wrap: anywhere; }
.preview-overview-reason-list span { display: block; margin-top: 5px; color: #aab8ca; font-size: 12px; }
.preview-overview-reason-list p { margin: 5px 0 0; color: #8fa2b9; font-size: 12px; line-height: 1.45; }
.preview-overview-empty-reason { margin: 0; color: #98a9bd; font-size: 13px; }
.preview-overview-agent-strip {
  display: grid;
  position: static;
  float: none;
  transform: none;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  min-width: 0;
  height: auto;
  border-top: 1px solid #233b55;
  background: rgba(5, 14, 25, .45);
}
.preview-overview-agent-strip div { min-width: 0; padding: 10px 12px; border-right: 1px solid #20354d; }
.preview-overview-agent-strip div:last-child { border-right: 0; }
.preview-overview-agent-strip span, .preview-overview-agent-strip b {
  display: block;
  overflow-wrap: anywhere;
}
.preview-overview-agent-strip span { margin: 5px 0 3px; color: #bac7d6; font-size: 12px; }
.preview-overview-agent-strip b { color: #879bb3; font-size: 11px; }
.preview-overview-status-dot {
  display: block;
  width: 7px;
  aspect-ratio: 1;
  border-radius: 50%;
  background: #71849a;
}
.preview-overview-status-dot.pass { background: #54c394; }
.preview-overview-status-dot.warn, .preview-overview-status-dot.not_applicable { background: #d69b45; }
.preview-overview-status-dot.blocked { background: #dd626e; }
@media (max-width: 1500px) {
  .preview-overview-reason-list { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 1100px) {
  .preview-overview-decision { grid-template-columns: 1fr; }
  .preview-overview-reason-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .preview-overview-agent-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
</style>
